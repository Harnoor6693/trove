package finance.trove.networth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import finance.trove.accounts.ConnectedAccountRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.networth.dto.LiabilityRequest;
import finance.trove.networth.dto.LiabilityUpdateRequest;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link LiabilityService} — currency defaulting, zero-interest default, the account guard. */
@ExtendWith(MockitoExtension.class)
class LiabilityServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Mock
    private LiabilityRepository liabilities;

    @Mock
    private ConnectedAccountRepository accounts;

    @Mock
    private UserRepository users;

    private LiabilityService service() {
        return new LiabilityService(liabilities, accounts, users);
    }

    private void savePassesThrough() {
        when(liabilities.save(any(Liability.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("currency defaults to the CREATING member's own users.currency when omitted")
    void currencyDefaultsToCreatorsOwn() {
        User creator = NetWorthFixtures.member(USER, HOUSEHOLD, UserRole.MEMBER, ZoneId.of("UTC"),
            CurrencyCode.USD);
        when(users.findByIdAndHouseholdId(USER, HOUSEHOLD)).thenReturn(Optional.of(creator));
        savePassesThrough();
        LiabilityRequest request = new LiabilityRequest("Visa", LiabilityType.CREDIT_CARD,
            new BigDecimal("100.00"), null, null, null);

        Liability created = service().create(HOUSEHOLD, USER, request);

        assertThat(created.getCurrency()).isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("An omitted interestRate defaults to zero (zero-interest debt)")
    void interestRateDefaultsToZero() {
        savePassesThrough();
        LiabilityRequest request = new LiabilityRequest("Loan", LiabilityType.STUDENT_LOAN,
            new BigDecimal("1000.00"), null, CurrencyCode.CAD, null);

        Liability created = service().create(HOUSEHOLD, USER, request);

        assertThat(created.getInterestRate()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("An accountId naming another household's account is 400 validation-error")
    void foreignAccountIdIsRejectedOnCreate() {
        UUID foreignAccount = UUID.randomUUID();
        when(accounts.existsByIdAndHouseholdId(foreignAccount, HOUSEHOLD)).thenReturn(false);
        LiabilityRequest request = new LiabilityRequest("Visa", LiabilityType.CREDIT_CARD,
            new BigDecimal("100.00"), null, CurrencyCode.CAD, foreignAccount);

        assertThatThrownBy(() -> service().create(HOUSEHOLD, USER, request))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Updating a liability in another household is 404, never 403")
    void updatingAForeignLiabilityIsNotFound() {
        UUID liabilityId = UUID.randomUUID();
        when(liabilities.findByIdAndHouseholdId(liabilityId, HOUSEHOLD))
            .thenReturn(Optional.empty());
        LiabilityUpdateRequest request = new LiabilityUpdateRequest("Renamed",
            LiabilityType.CREDIT_CARD, new BigDecimal("50.00"), null, null);

        assertThatThrownBy(() -> service().update(HOUSEHOLD, liabilityId, request))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Deleting a liability in another household is 404, never 403")
    void deletingAForeignLiabilityIsNotFound() {
        UUID liabilityId = UUID.randomUUID();
        when(liabilities.deleteByIdAndHouseholdId(liabilityId, HOUSEHOLD)).thenReturn(0L);

        assertThatThrownBy(() -> service().delete(HOUSEHOLD, liabilityId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
