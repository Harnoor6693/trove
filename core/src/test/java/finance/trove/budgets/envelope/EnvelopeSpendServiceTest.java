package finance.trove.budgets.envelope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.budgets.period.BudgetPeriodRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.events.BudgetThresholdCrossedEvent;
import finance.trove.common.plugin.TransactionType;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@link EnvelopeSpendService} — the aggregate finding D1 says was never written.
 *
 * <p>These assert the arithmetic and the seam, by behaviour rather than by status code: that the
 * writer <em>rewrites</em> rather than adds, that a {@code REFUND} nets down, that a row in
 * another currency is invisible, and that the read goes through the one query that excludes
 * transfer legs (gap P2-14) rather than through a fresh one of this package's own.
 */
@ExtendWith(MockitoExtension.class)
class EnvelopeSpendServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID GROCERIES = UUID.randomUUID();
    private static final UUID RESTAURANTS = UUID.randomUUID();
    private static final UUID PERIOD = UUID.randomUUID();
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    @Mock
    private EnvelopeRepository envelopes;

    @Mock
    private BudgetPeriodRepository periods;

    @Mock
    private TransactionQueryService transactions;

    @Mock
    private ApplicationEventPublisher events;

    private EnvelopeSpendService service() {
        return serviceWithThresholds(new BigDecimal("0.80"));
    }

    /**
     * The service with an explicit set of household thresholds.
     *
     * <p>Collapse step 11 replaced the single configured ratio with
     * {@link HouseholdAlertThresholds}, whose production implementation reads every distinct value
     * the household's members hold in {@code notification_preference} (V31). The cases below keep
     * using 80 % so that they assert the same arithmetic they always did; the multi-threshold case
     * is {@link #everyMembersThresholdIsWatched()}.
     */
    private EnvelopeSpendService serviceWithThresholds(BigDecimal... fractions) {
        Set<BigDecimal> set = Set.of(fractions);
        return new EnvelopeSpendService(envelopes, periods, transactions, events,
            householdId -> set);
    }

    /**
     * An envelope with an id, which a fixture that never reaches the database would not otherwise
     * have — {@link BudgetThresholdCrossedEvent} requires one, and a null there would be a fact
     * about the test rather than about the code.
     */
    private static Envelope envelope(UUID categoryId, String allocated, CurrencyCode currency) {
        Envelope envelope = new Envelope(HOUSEHOLD, PERIOD, categoryId, new BigDecimal(allocated),
            currency, Instant.parse("2026-09-01T00:00:00Z"));
        ReflectionTestUtils.setField(envelope, "id", UUID.randomUUID());
        return envelope;
    }

    private static Transaction row(String amount, CurrencyCode currency, TransactionType type,
                                   UUID categoryId, int dayOfMonth) {
        Transaction transaction = new Transaction(HOUSEHOLD, UUID.randomUUID(), MEMBER, null,
            new BigDecimal(amount), currency, type, "row", "MERCHANT",
            LocalDate.of(2026, 9, dayOfMonth), null, false, true,
            Instant.parse("2026-09-01T00:00:00Z"));
        transaction.setCategoryId(categoryId);
        return transaction;
    }

    private void ledgerReturns(Transaction... rows) {
        when(transactions.findSpendableInRange(HOUSEHOLD, LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30))).thenReturn(List.of(rows));
    }

    private void envelopeIs(Envelope envelope) {
        when(envelopes.findForCategoryAndMonth(HOUSEHOLD, envelope.getCategoryId(),
            LocalDate.of(2026, 9, 1))).thenReturn(Optional.of(envelope));
    }

    @Test
    @DisplayName("spent is the sum of the month's debits in this category and currency")
    void spentIsTheSumOfTheMonthsDebits() {
        Envelope groceries = envelope(GROCERIES, "400.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        ledgerReturns(
            row("30.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
            row("45.50", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 9),
            row("24.50", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 20));

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        assertThat(groceries.getSpent()).isEqualByComparingTo(new BigDecimal("100.00"));
        verify(envelopes).save(groceries);
    }

    @Test
    @DisplayName("a REFUND nets down the spend it reverses")
    void aRefundNetsDown() {
        Envelope groceries = envelope(GROCERIES, "400.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        ledgerReturns(
            row("100.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
            row("30.00", CurrencyCode.CAD, TransactionType.REFUND, GROCERIES, 5));

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        assertThat(groceries.getSpent())
            .as("DEBIT less REFUND — the identical rule DashboardService states, so the two "
                + "screens cannot disagree about whether a returned purchase reduces spending")
            .isEqualByComparingTo(new BigDecimal("70.00"));
    }

    @Test
    @DisplayName("income, fees and interest are movements but are not spending")
    void onlyDebitAndRefundContribute() {
        Envelope groceries = envelope(GROCERIES, "400.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        ledgerReturns(
            row("50.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
            row("900.00", CurrencyCode.CAD, TransactionType.CREDIT, GROCERIES, 3),
            row("12.00", CurrencyCode.CAD, TransactionType.FEE, GROCERIES, 4),
            row("3.00", CurrencyCode.CAD, TransactionType.INTEREST, GROCERIES, 5),
            row("7.00", CurrencyCode.CAD, TransactionType.OTHER, GROCERIES, 6));

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        assertThat(groceries.getSpent()).isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    @DisplayName("a row in another currency is invisible to this envelope")
    void aRowInAnotherCurrencyIsInvisible() {
        Envelope canadianGroceries = envelope(GROCERIES, "400.00", CurrencyCode.CAD);
        envelopeIs(canadianGroceries);
        ledgerReturns(
            row("40.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
            row("60.00", CurrencyCode.USD, TransactionType.DEBIT, GROCERIES, 3));

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        assertThat(canadianGroceries.getSpent())
            .as("there is no FX rate anywhere in this system, so 60 USD is not 60 CAD and is not "
                + "some converted number either — it belongs to a USD envelope or to none")
            .isEqualByComparingTo(new BigDecimal("40.00"));
    }

    @Test
    @DisplayName("a row of another category is invisible to this envelope")
    void aRowOfAnotherCategoryIsInvisible() {
        Envelope groceries = envelope(GROCERIES, "400.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        ledgerReturns(
            row("40.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
            row("500.00", CurrencyCode.CAD, TransactionType.DEBIT, RESTAURANTS, 3),
            row("11.00", CurrencyCode.CAD, TransactionType.DEBIT, null, 4));

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        assertThat(groceries.getSpent()).isEqualByComparingTo(new BigDecimal("40.00"));
    }

    @Test
    @DisplayName("transfer legs are excluded by the shared query, not re-derived here")
    void transferLegsAreExcludedByTheSharedSeam() {
        Envelope groceries = envelope(GROCERIES, "400.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        ledgerReturns(row("40.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2));

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        // The exclusion is gap P2-14's and belongs to TransactionQueryService, whose javadoc names
        // this step as a caller precisely so it is inherited. Asserting the seam is what stops a
        // later refactor quietly swapping in a query of this package's own that forgets it —
        // EnvelopeSpendServiceIT then proves the exclusion itself against a real ledger.
        verify(transactions).findSpendableInRange(HOUSEHOLD, LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("D1/risk 4: recategorising the same transaction twice leaves the SUM, not a sum "
        + "of sums")
    void theAggregateIsARewriteAndNotAnIncrement() {
        Envelope groceries = envelope(GROCERIES, "400.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        ledgerReturns(
            row("30.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
            row("70.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 9));

        EnvelopeSpendService service = service();
        service.recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);
        BigDecimal afterFirst = groceries.getSpent();
        service.recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);
        service.recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        assertThat(afterFirst).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(groceries.getSpent())
            .as("an incrementing writer would read 300.00 after three passes; this is the whole "
                + "reason recategorisation and re-sync cannot double count (§9 risk 4)")
            .isEqualByComparingTo(new BigDecimal("100.00"));
        verify(envelopes, times(1)).save(groceries);
    }

    @Test
    @DisplayName("a month with no envelope for the category writes nothing and invents nothing")
    void noEnvelopeIsANoOp() {
        when(envelopes.findForCategoryAndMonth(HOUSEHOLD, GROCERIES, LocalDate.of(2026, 9, 1)))
            .thenReturn(Optional.empty());

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        verify(envelopes, never()).save(any());
        verifyNoInteractions(transactions);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("a null category is a no-op: an uncategorised transaction budgets nothing")
    void aNullCategoryIsANoOp() {
        service().recalculate(HOUSEHOLD, MEMBER, null, SEPTEMBER);

        verifyNoInteractions(envelopes);
        verifyNoInteractions(transactions);
    }

    @Test
    @DisplayName("the three-argument seam is a no-op on a null category, as its javadoc promises")
    void theSeamFormIsANoOpOnANullCategory() {
        // The §1.3 seam other packages call, in the form a scheduled caller uses. Its javadoc
        // documents a null category as a no-op; without the guard the body builds a CategoryMonth,
        // whose compact constructor requires a non-null id, and the seam throws NPE instead.
        assertThatCode(() -> service().recalculate(HOUSEHOLD, null, SEPTEMBER))
            .doesNotThrowAnyException();

        verifyNoInteractions(envelopes);
        verifyNoInteractions(transactions);
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("a batch reads each distinct month once, however many categories it names")
    void aBatchReadsEachMonthOnce() {
        Envelope groceries = envelope(GROCERIES, "400.00", CurrencyCode.CAD);
        Envelope restaurants = envelope(RESTAURANTS, "200.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        envelopeIs(restaurants);
        ledgerReturns(
            row("40.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
            row("15.00", CurrencyCode.CAD, TransactionType.DEBIT, RESTAURANTS, 3));

        service().recalculateAll(HOUSEHOLD, MEMBER, List.of(
            new CategoryMonth(GROCERIES, SEPTEMBER),
            new CategoryMonth(RESTAURANTS, SEPTEMBER),
            // The duplicate is what a sync of forty grocery rows actually produces.
            new CategoryMonth(GROCERIES, SEPTEMBER)));

        assertThat(groceries.getSpent()).isEqualByComparingTo(new BigDecimal("40.00"));
        assertThat(restaurants.getSpent()).isEqualByComparingTo(new BigDecimal("15.00"));
        verify(transactions, times(1)).findSpendableInRange(HOUSEHOLD, LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30));
    }

    @Test
    @DisplayName("the threshold event fires on the crossing, once, and not on later writes")
    void thresholdFiresOnTheCrossingOnly() {
        Envelope groceries = envelope(GROCERIES, "100.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        EnvelopeSpendService service = service();

        // 70 of 100 — under the 80 % trigger.
        when(transactions.findSpendableInRange(HOUSEHOLD, LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30)))
            .thenReturn(List.of(row("70.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES,
                2)))
            .thenReturn(List.of(
                row("70.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
                row("15.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 5)))
            .thenReturn(List.of(
                row("70.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
                row("15.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 5),
                row("5.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 6)));

        service.recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);
        service.recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);
        service.recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(published.capture());
        BudgetThresholdCrossedEvent event =
            (BudgetThresholdCrossedEvent) published.getValue();
        assertThat(event.spent().amount())
            .as("published with the figure that crossed, not with a later one")
            .isEqualByComparingTo(new BigDecimal("85.00"));
        assertThat(event.allocated().amount()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(event.month()).isEqualTo(SEPTEMBER);
        assertThat(event.userId()).isEqualTo(MEMBER);
        assertThat(groceries.getSpent())
            .as("the third write still happened; only the alert was suppressed")
            .isEqualByComparingTo(new BigDecimal("90.00"));
    }

    @Test
    @DisplayName("B2-07: the threshold is a share of allocated plus rollover, not of allocated")
    void thresholdCountsTheRollover() {
        Envelope rolled = envelope(GROCERIES, "100.00", CurrencyCode.CAD);
        rolled.setRolloverAmount(new BigDecimal("100.00"));
        envelopeIs(rolled);
        ledgerReturns(row("85.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2));

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);
        verify(events, never()).publishEvent(any(Object.class));

        Envelope carriedOnly = envelope(GROCERIES, "0.00", CurrencyCode.CAD);
        carriedOnly.setRolloverAmount(new BigDecimal("50.00"));
        envelopeIs(carriedOnly);
        ledgerReturns(row("45.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2));

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);
        verify(events, times(1)).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("an envelope with nothing allocated never crosses a threshold")
    void nothingAllocatedNeverAlerts() {
        Envelope groceries = envelope(GROCERIES, "0.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        ledgerReturns(row("40.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2));

        service().recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        assertThat(groceries.getSpent()).isEqualByComparingTo(new BigDecimal("40.00"));
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("with no acting member, the alert is addressed to whoever opened the period")
    void theAlertFallsBackToThePeriodsCreator() {
        Envelope groceries = envelope(GROCERIES, "100.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        ledgerReturns(row("90.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2));
        when(periods.findByIdAndHouseholdId(eq(PERIOD), eq(HOUSEHOLD)))
            .thenReturn(Optional.of(new finance.trove.budgets.period.BudgetPeriod(HOUSEHOLD,
                MEMBER, SEPTEMBER, new BigDecimal("1000.00"), CurrencyCode.CAD,
                finance.trove.budgets.period.BudgetType.FLEXIBLE,
                Instant.parse("2026-09-01T00:00:00Z"))));

        // The three-argument form is the seam §1.3 names; a scheduled caller has no request behind
        // it and therefore no acting member of its own.
        service().recalculate(HOUSEHOLD, GROCERIES, SEPTEMBER);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(((BudgetThresholdCrossedEvent) published.getValue()).userId()).isEqualTo(MEMBER);
    }

    @Test
    @DisplayName("every member's own threshold is watched, not just the lowest")
    void everyMembersThresholdIsWatched() {
        // Two members of one household: one wants to hear at 70 %, the other at 95 %. A single
        // ratio cannot serve both. If the envelope watched only the lowest, the crossing at 70
        // would be consumed and the 95 % member would never be alerted at all; if it watched only
        // the highest, the 70 % member would hear far too late. Both crossings publish, and the
        // notification listener decides which member each one concerns.
        EnvelopeSpendService service =
            serviceWithThresholds(new BigDecimal("0.70"), new BigDecimal("0.95"));
        Envelope groceries = envelope(GROCERIES, "100.00", CurrencyCode.CAD);
        envelopeIs(groceries);
        when(transactions.findSpendableInRange(HOUSEHOLD, LocalDate.of(2026, 9, 1),
            LocalDate.of(2026, 9, 30)))
            .thenReturn(List.of(row("72.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES,
                2)))
            .thenReturn(List.of(
                row("72.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
                row("10.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 5)))
            .thenReturn(List.of(
                row("72.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 2),
                row("10.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 5),
                row("14.00", CurrencyCode.CAD, TransactionType.DEBIT, GROCERIES, 6)));

        service.recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);
        service.recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);
        service.recalculate(HOUSEHOLD, MEMBER, GROCERIES, SEPTEMBER);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publishEvent(published.capture());
        assertThat(published.getAllValues())
            .as("72 crosses 70 %, 82 crosses nothing, 96 crosses 95 %")
            .extracting(event -> ((BudgetThresholdCrossedEvent) event).spent().amount())
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("72.00"), new BigDecimal("96.00"));
    }
}
