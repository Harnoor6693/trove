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
    @DisplayName("A hand-made account is saved once, with its own primary key as external id")
    void aHandMadeAccountGetsItsOwnIdAsExternalId() {
        repositoryAssignsAnId();

        ConnectedAccount created = service().create(HOUSEHOLD, USER, request());

        verify(accounts).save(any(ConnectedAccount.class));
        verify(accounts, never()).saveAndFlush(any(ConnectedAccount.class));
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
        when(accounts.saveAndFlush(any(ConnectedAccount.class))).thenThrow(violation);

        AccountService service = service();
        AccountRequest request = request();

        assertThatThrownBy(() -> service.create(HOUSEHOLD, USER, request))
            .isSameAs(violation)
            .isNotInstanceOf(ConflictException.class);
    }
}
