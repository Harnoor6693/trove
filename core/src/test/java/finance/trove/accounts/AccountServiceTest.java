package finance.trove.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.accounts.dto.AccountRequest;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.transactions.TransactionFixtures;
import finance.trove.users.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The write path of {@link AccountService}, at the one point where a mocked repository can say
 * something an integration test cannot.
 *
 * <p>{@code create} advertises a 409 for a household that already holds an account for the
 * provider account being described, and {@code openapi/trove-core.yaml} documents it. Whether
 * that 409 is <em>producible</em> depends entirely on when the INSERT reaches the database:
 * {@code save()} on an entity whose UUID is generated in memory only queues it, so with a bare
 * {@code save()} the violation is raised at commit — after the method's own {@code catch} has
 * been left — and the specific conflict is unreachable code. {@code saveAndFlush} is what forces
 * the write inside the guarded region, and that is what these two tests hold in place.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock
    private ConnectedAccountRepository accounts;

    @Mock
    private UserRepository users;

    private final UserClock clock = new UserClock(
        (UserTimeZoneProvider) userId -> ZoneId.of("UTC"),
        Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC));

    private AccountService service() {
        return new AccountService(accounts, users, clock);
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
    @DisplayName("The insert is FLUSHED inside the try, so a slot collision is a 409 and not "
        + "an exception raised at commit with the catch already left behind")
    void theInsertIsFlushedInsideTheGuardedRegion() {
        repositoryAssignsAnId();
        when(accounts.saveAndFlush(any(ConnectedAccount.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        ConnectedAccount created = service().create(HOUSEHOLD, USER, request());

        verify(accounts).saveAndFlush(any(ConnectedAccount.class));
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
    @DisplayName("A uniqueness violation from that flush is the documented conflict, with the "
        + "wording that names the source kind")
    void aSlotCollisionIsTheDocumentedConflict() {
        repositoryAssignsAnId();
        when(accounts.saveAndFlush(any(ConnectedAccount.class)))
            .thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint "
                    + "\"connected_account_household_id_source_kind_external_account_id_key\""));

        AccountService service = service();
        AccountRequest request = request();

        assertThatThrownBy(() -> service.create(HOUSEHOLD, USER, request))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("MANUAL")
            .hasMessageContaining("Reuse it");
    }
}
