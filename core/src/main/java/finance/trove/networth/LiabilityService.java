package finance.trove.networth;

import finance.trove.accounts.ConnectedAccountRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.time.Instants;
import finance.trove.networth.dto.LiabilityRequest;
import finance.trove.networth.dto.LiabilityUpdateRequest;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over a household's {@link Liability}s. See {@link AssetService}'s javadoc — the same
 * 404-not-403 ownership pattern and account-linking rule apply.
 */
@Service
public class LiabilityService {

    private final LiabilityRepository liabilities;
    private final ConnectedAccountRepository accounts;
    private final UserRepository users;

    public LiabilityService(LiabilityRepository liabilities, ConnectedAccountRepository accounts,
                            UserRepository users) {
        this.liabilities = liabilities;
        this.accounts = accounts;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<Liability> list(UUID householdId) {
        return liabilities.findAllByHouseholdIdOrderByCreatedAtDesc(householdId);
    }

    /**
     * Creates a liability. {@code currency} defaults to the creating member's own
     * {@code users.currency} when omitted; {@code interestRate} defaults to zero. No literal
     * default anywhere (§9 risk 5).
     */
    @Transactional
    public Liability create(UUID householdId, UUID userId, LiabilityRequest request) {
        CurrencyCode currency = request.currency() != null ? request.currency()
            : requireCreatingUser(householdId, userId).getCurrency();
        UUID accountId = requireAccountInHousehold(householdId, request.accountId());
        BigDecimal interestRate =
            request.interestRate() != null ? request.interestRate() : BigDecimal.ZERO;
        Liability liability = new Liability(householdId, userId, request.name(),
            request.liabilityType(), currency.normalise(request.balance()), interestRate, currency,
            accountId, Instants.now());
        return liabilities.save(liability);
    }

    /**
     * Updates a liability. Full replace of the mutable fields — {@code currency} is never
     * writable here. An omitted {@code interestRate} resets to zero, matching {@code create}'s
     * default: this is a full replace, not a partial patch, so there is no "leave unchanged"
     * available to a plain omitted field.
     */
    @Transactional
    public Liability update(UUID householdId, UUID liabilityId, LiabilityUpdateRequest request) {
        Liability liability = liabilities.findByIdAndHouseholdId(liabilityId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Liability", liabilityId));
        UUID accountId = requireAccountInHousehold(householdId, request.accountId());
        BigDecimal interestRate =
            request.interestRate() != null ? request.interestRate() : BigDecimal.ZERO;
        liability.setName(request.name());
        liability.setLiabilityType(request.liabilityType());
        liability.setBalance(liability.getCurrency().normalise(request.balance()));
        liability.setInterestRate(interestRate);
        liability.setAccountId(accountId);
        return liabilities.save(liability);
    }

    @Transactional
    public void delete(UUID householdId, UUID liabilityId) {
        long removed = liabilities.deleteByIdAndHouseholdId(liabilityId, householdId);
        if (removed == 0) {
            throw ResourceNotFoundException.of("Liability", liabilityId);
        }
    }

    private User requireCreatingUser(UUID householdId, UUID userId) {
        return users.findByIdAndHouseholdId(userId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    /** See {@link AssetService#requireAccountInHousehold(UUID, UUID)}'s javadoc. */
    private UUID requireAccountInHousehold(UUID householdId, UUID accountId) {
        if (accountId == null) {
            return null;
        }
        if (!accounts.existsByIdAndHouseholdId(accountId, householdId)) {
            throw new ValidationException(
                "accountId does not name a connected account this household can use");
        }
        return accountId;
    }
}
