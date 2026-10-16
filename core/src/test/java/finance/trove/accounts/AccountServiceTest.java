package finance.trove.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.accounts.dto.AccountRequest;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.events.TransactionCategorisedEvent;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.transactions.TransactionFixtures;
import finance.trove.users.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The write path of {@link AccountService}, at the one point where a mocked repository can say
 * something an integration test cannot.
 *
 * <p>{@code create} gives the account its own primary key as its external id, so the unique key
 * on {@code (household_id, source_kind, external_account_id)} cannot collide for a hand-made
 * account. Any {@link DataIntegrityViolationException} is therefore not "this household already
 * has an account of that kind" (CI-41), and the service must not say so: it lets the exception
 * reach the global handler, which does not claim a cause it has not established.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock
    private ConnectedAccountRepository accounts;

    @Mock
    private UserRepository users;

    @Mock
    private ApplicationEventPublisher events;

    private final UserClock clock = new UserClock(
        (UserTimeZoneProvider) userId -> ZoneId.of("UTC"),
        Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC));

    private AccountService service() {
        return new AccountService(accounts, users, clock, events);
    }

    private static AccountRequest request() {
        return new AccountRequest(SourceKind.MANUAL, "  Chequing  ", "  Bank  ",
            AccountType.CHECKING, CurrencyCode.CAD);
    }

    private void repositoryAssignsAnId() {
        when(accounts.save(any(ConnectedAccount.class))).thenAnswer(invocation -> {
            ConnectedAccount entity = invocation.getArgument(0);
            return TransactionFixtures.withId(entity, UUID.randomUUID());
        });
    }

    @Test
    @DisplayName("A hand-made account is saved once, with its own primary key as external id")
    void aHandMadeAccountGetsItsOwnIdAsExternalId() {
        repositoryAssignsAnId();

        ConnectedAccount created = service().create(HOUSEHOLD, USER, request());

        verify(accounts).save(any(ConnectedAccount.class));
        assertThat(created.getExternalAccountId())
            .as("an account typed in by hand has no provider identity, so it is given its own "
                + "primary key: V18's uniqueness key treats nulls as EQUAL, and a household needs "
                + "more than one manual account")
            .isEqualTo(created.getId().toString());
        assertThat(created.getDisplayName()).isEqualTo("Chequing");
        assertThat(created.getInstitution()).isEqualTo("Bank");
        verify(users, never()).findByIdAndHouseholdId(any(), any());
    }

    @Test
    @DisplayName("A referenced-row violation is not reported as \"already has an account\"")
    void aDataIntegrityViolationIsNotMistakenForADuplicateAccount() {
        DataIntegrityViolationException violation = new DataIntegrityViolationException(
            "insert or update on table \"connected_account\" violates foreign key constraint");
        when(accounts.save(any(ConnectedAccount.class))).thenThrow(violation);

        AccountService service = service();
        AccountRequest request = request();

        assertThatThrownBy(() -> service.create(HOUSEHOLD, USER, request))
            .isSameAs(violation)
            .isNotInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("G2: disconnecting captures first, then frees partners, deletes, and publishes "
        + "one event per category and month")
    void disconnectingRecomputesEachAffectedCategoryAndMonthOnce() {
        UUID account = UUID.randomUUID();
        UUID groceries = UUID.randomUUID();
        UUID restaurants = UUID.randomUUID();
        when(accounts.categorisedRowsAffectedByDisconnect(account, HOUSEHOLD)).thenReturn(List.of(
            new CategorisedRow(UUID.randomUUID(), groceries, LocalDate.of(2026, 9, 5),
                CurrencyCode.CAD),
            new CategorisedRow(UUID.randomUUID(), groceries, LocalDate.of(2026, 9, 28),
                CurrencyCode.CAD),
            new CategorisedRow(UUID.randomUUID(), restaurants, LocalDate.of(2026, 9, 7),
                CurrencyCode.CAD),
            new CategorisedRow(UUID.randomUUID(), groceries, LocalDate.of(2026, 10, 1),
                CurrencyCode.CAD)));
        when(accounts.deleteByIdAndHouseholdId(account, HOUSEHOLD)).thenReturn(1L);

        service().delete(HOUSEHOLD, USER, account);

        InOrder order = inOrder(accounts, events);
        order.verify(accounts).categorisedRowsAffectedByDisconnect(account, HOUSEHOLD);
        order.verify(accounts).freeTransferPartners(account, HOUSEHOLD);
        order.verify(accounts).deleteByIdAndHouseholdId(account, HOUSEHOLD);
        ArgumentCaptor<TransactionCategorisedEvent> published =
            ArgumentCaptor.forClass(TransactionCategorisedEvent.class);
        order.verify(events, times(3)).publishEvent(published.capture());
        assertThat(published.getAllValues())
            .extracting(TransactionCategorisedEvent::categoryId,
                TransactionCategorisedEvent::month)
            .containsExactly(
                tuple(groceries, YearMonth.of(2026, 9)),
                tuple(restaurants, YearMonth.of(2026, 9)),
                tuple(groceries, YearMonth.of(2026, 10)));
        assertThat(published.getAllValues()).allSatisfy(event -> {
            assertThat(event.previousCategoryId()).isEqualTo(event.categoryId());
            assertThat(event.householdId()).isEqualTo(HOUSEHOLD);
            assertThat(event.userId()).isEqualTo(USER);
            assertThat(event.currency()).isEqualTo(CurrencyCode.CAD);
        });
    }

    @Test
    @DisplayName("G2: an account that is not the household's is 404 and publishes nothing")
    void disconnectingAnUnknownAccountPublishesNothing() {
        UUID account = UUID.randomUUID();
        when(accounts.categorisedRowsAffectedByDisconnect(account, HOUSEHOLD))
            .thenReturn(List.of());
        when(accounts.deleteByIdAndHouseholdId(account, HOUSEHOLD)).thenReturn(0L);

        AccountService service = service();

        assertThatThrownBy(() -> service.delete(HOUSEHOLD, USER, account))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(events, never()).publishEvent(any(Object.class));
    }
}
