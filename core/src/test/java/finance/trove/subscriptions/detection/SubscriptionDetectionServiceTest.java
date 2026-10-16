package finance.trove.subscriptions.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.events.SubscriptionPriceChangedEvent;
import finance.trove.common.time.UserClock;
import finance.trove.subscriptions.alert.AlertType;
import finance.trove.subscriptions.alert.SubscriptionAlert;
import finance.trove.subscriptions.alert.SubscriptionAlertRepository;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What a scan does with the pre-image the upsert hands back: when it publishes
 * {@link SubscriptionPriceChangedEvent}, when it writes a {@code PRICE_INCREASE} alert, and the
 * three cases where it must do neither.
 *
 * <p>These are the branches an integration test cannot see from the outside — the event has no
 * consumer yet (notifications are step 11, webhooks step 16), so "was it published, exactly once"
 * is only observable here.
 */
class SubscriptionDetectionServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID SUBSCRIPTION = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 5);

    private final TransactionQueryService transactions = mock(TransactionQueryService.class);
    private final CandidateFinder candidates = mock(CandidateFinder.class);
    private final SubscriptionUpsert upsert = mock(SubscriptionUpsert.class);
    private final SubscriptionAlertRepository alerts = mock(SubscriptionAlertRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserClock clock = mock(UserClock.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final SubscriptionDetectionProperties properties =
        new SubscriptionDetectionProperties(null, null, null, null, null, null, null);

    private SubscriptionDetectionService service;

    @BeforeEach
    void setUp() {
        User admin = mock(User.class);
        when(admin.getId()).thenReturn(ADMIN);
        when(admin.getRole()).thenReturn(UserRole.ADMIN);
        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(HOUSEHOLD)).thenReturn(List.of(admin));
        when(clock.today(ADMIN)).thenReturn(TODAY);
        service = new SubscriptionDetectionService(transactions, candidates, upsert, alerts, users,
            clock, events, properties);
    }

    @Test
    @DisplayName("A raised price publishes exactly one event and writes exactly one alert")
    void aRaisedPricePublishesOneEventAndWritesOneAlert() {
        givenTheScanFinds("SPOTIFY", "12.99", CurrencyCode.CAD);
        givenTheStoredRowWas("9.99", CurrencyCode.CAD, false);

        assertThat(service.detectForHousehold(HOUSEHOLD)).isEqualTo(1);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(published.capture());
        SubscriptionPriceChangedEvent event =
            (SubscriptionPriceChangedEvent) published.getValue();
        assertThat(event.householdId()).isEqualTo(HOUSEHOLD);
        assertThat(event.subscriptionId()).isEqualTo(SUBSCRIPTION);
        assertThat(event.merchantName()).isEqualTo("SPOTIFY");
        assertThat(event.previousAmount().amount()).isEqualByComparingTo("9.99");
        assertThat(event.newAmount().amount()).isEqualByComparingTo("12.99");
        assertThat(event.delta().amount()).isEqualByComparingTo("3.00");

        ArgumentCaptor<SubscriptionAlert> alert = ArgumentCaptor.forClass(SubscriptionAlert.class);
        verify(alerts, times(1)).save(alert.capture());
        assertThat(alert.getValue().getAlertType()).isEqualTo(AlertType.PRICE_INCREASE);
        assertThat(alert.getValue().getSubscriptionId()).isEqualTo(SUBSCRIPTION);
        assertThat(alert.getValue().getHouseholdId()).isEqualTo(HOUSEHOLD);
        assertThat(alert.getValue().getAlertMessage())
            .as("both amounts, with their currency, and no CAD literal anywhere in the code")
            .contains("SPOTIFY", "9.99 CAD", "12.99 CAD");
        assertThat(alert.getValue().isRead()).isFalse();
    }

    @Test
    @DisplayName("A second scan over unchanged history publishes nothing and writes no alert")
    void anUnchangedScanIsSilent() {
        givenTheScanFinds("SPOTIFY", "9.99", CurrencyCode.CAD);
        // The upsert's IS DISTINCT FROM comparisons all come out false, so the pre-image it hands
        // back carries the amount that was just written.
        givenTheStoredRowWas("9.99", CurrencyCode.CAD, false);

        service.detectForHousehold(HOUSEHOLD);

        verifyNoInteractions(events);
        verify(alerts, never()).save(any());
    }

    @Test
    @DisplayName("A first detection has nothing to compare with, so it announces nothing")
    void anInsertAnnouncesNothing() {
        givenTheScanFinds("SPOTIFY", "9.99", CurrencyCode.CAD);
        when(upsert.apply(eq(HOUSEHOLD), eq(ADMIN), any()))
            .thenReturn(new SubscriptionUpsert.Outcome(SUBSCRIPTION, null, null, false));

        service.detectForHousehold(HOUSEHOLD);

        verifyNoInteractions(events);
        verify(alerts, never()).save(any());
    }

    @Test
    @DisplayName("A price DROP publishes the (signed) event but writes no alert")
    void aPriceDropPublishesWithoutAnAlert() {
        givenTheScanFinds("SPOTIFY", "7.99", CurrencyCode.CAD);
        givenTheStoredRowWas("9.99", CurrencyCode.CAD, false);

        service.detectForHousehold(HOUSEHOLD);

        verify(events, times(1)).publishEvent(any(SubscriptionPriceChangedEvent.class));
        verify(alerts, never()).save(any());
    }

    @Test
    @DisplayName("A currency change is not a price change: no event, no alert, no exception")
    void aCurrencyChangeIsNotAPriceChange() {
        givenTheScanFinds("NEWS", "6.00", CurrencyCode.USD);
        givenTheStoredRowWas("8.00", CurrencyCode.CAD, false);

        // SubscriptionPriceChangedEvent's own constructor REFUSES a pair whose currencies differ,
        // so constructing one here would throw out of the scan. The comparison is made before the
        // event is built, which is the whole point.
        assertThat(service.detectForHousehold(HOUSEHOLD)).isEqualTo(1);

        verifyNoInteractions(events);
        verify(alerts, never()).save(any());
    }

    @Test
    @DisplayName("A dismissed subscription never raises another alert or event")
    void aCancelledSubscriptionIsSilent() {
        givenTheScanFinds("SPOTIFY", "12.99", CurrencyCode.CAD);
        givenTheStoredRowWas("9.99", CurrencyCode.CAD, true);

        service.detectForHousehold(HOUSEHOLD);

        verifyNoInteractions(events);
        verify(alerts, never()).save(any());
    }

    @Test
    @DisplayName("A household with no members is skipped rather than treated as an error")
    void aHouseholdWithNoMembersIsSkipped() {
        UUID empty = UUID.randomUUID();
        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(empty)).thenReturn(List.of());

        assertThat(service.detectForHousehold(empty)).isZero();
        verifyNoInteractions(transactions, events);
    }

    @Test
    @DisplayName("Three annual charges, the oldest two years back, fall inside the default window")
    void threeAnnualChargesFitTheDefaultWindow() {
        when(transactions.debitsSince(eq(HOUSEHOLD), any())).thenReturn(List.of());
        // The latest charge is ten days old, so the oldest of three is two years and ten days
        // back. With the old thirteen-month window an annual subscription could never have the
        // three charges the classifier needs (CI-45).
        LocalDate oldestOfThree = TODAY.minusYears(2).minusDays(10);

        service.detectForHousehold(HOUSEHOLD);

        ArgumentCaptor<LocalDate> since = ArgumentCaptor.forClass(LocalDate.class);
        verify(transactions).debitsSince(eq(HOUSEHOLD), since.capture());
        assertThat(oldestOfThree).isAfterOrEqualTo(since.getValue());
    }

    @Test
    @DisplayName("The window is 25 months back from the household's own today")
    void theWindowIs25MonthsFromTheHouseholdsToday() {
        when(transactions.debitsSince(eq(HOUSEHOLD), any())).thenReturn(List.of());

        service.detectForHousehold(HOUSEHOLD);

        verify(transactions).debitsSince(HOUSEHOLD, TODAY.minusMonths(25));
    }

    @Test
    @DisplayName("A merchant with fewer than the minimum charges never reaches the classifier")
    void tooFewChargesAreSkippedBeforeTheClassifier() {
        when(transactions.debitsSince(eq(HOUSEHOLD), any())).thenReturn(List.of(
            SubscriptionFixtures.charge("SPOTIFY", "9.99", TODAY.minusDays(30), CurrencyCode.CAD),
            SubscriptionFixtures.charge("SPOTIFY", "9.99", TODAY, CurrencyCode.CAD)));

        assertThat(service.detectForHousehold(HOUSEHOLD)).isZero();
        verifyNoInteractions(candidates, upsert);
    }

    /** The scan finds one candidate for one merchant. */
    private void givenTheScanFinds(String merchant, String amount, CurrencyCode currency) {
        List<Transaction> charges =
            SubscriptionFixtures.series(merchant, amount, TODAY.minusDays(1),
                BillingCycle.MONTHLY, 3, currency);
        when(transactions.debitsSince(eq(HOUSEHOLD), any())).thenReturn(charges);
        when(candidates.find(eq(merchant), any(), eq(TODAY))).thenReturn(Optional.of(
            new SubscriptionCandidate(merchant, currency, new BigDecimal(amount),
                BillingCycle.MONTHLY, TODAY.plusDays(29), false, TODAY.minusDays(61),
                TODAY.minusDays(1), null, BigDecimal.ONE,
                charges.stream().map(Transaction::getId).toList())));
    }

    /** What the upsert's {@code before} CTE found. */
    private void givenTheStoredRowWas(String amount, CurrencyCode currency, boolean cancelled) {
        when(upsert.apply(eq(HOUSEHOLD), eq(ADMIN), any())).thenReturn(
            new SubscriptionUpsert.Outcome(SUBSCRIPTION, new BigDecimal(amount), currency,
                cancelled));
    }
}
