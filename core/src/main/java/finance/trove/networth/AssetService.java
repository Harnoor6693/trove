package finance.trove.networth;

import finance.trove.accounts.ConnectedAccountRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.networth.dto.AssetRequest;
import finance.trove.networth.dto.AssetUpdateRequest;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over a household's {@link Asset}s ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6
 * "Net worth").
 *
 * <p>Ownership is 404-not-403 by construction: every read and write goes through
 * {@link AssetRepository}'s household-scoped finders, inherited from {@link
 * finance.trove.common.repository.UserScopedRepository}, so an id belonging to another household
 * simply is not found — never a 403 that would confirm the row exists (§9 risk 2).
 */
@Service
public class AssetService {

    private final AssetRepository assets;
    private final ConnectedAccountRepository accounts;
    private final UserRepository users;

    public AssetService(AssetRepository assets, ConnectedAccountRepository accounts,
                        UserRepository users) {
        this.assets = assets;
        this.accounts = accounts;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<Asset> list(UUID householdId) {
        return assets.findAllByHouseholdIdOrderByCreatedAtDesc(householdId);
    }

    /**
     * Creates an asset. {@code currency} defaults to the creating member's own
     * {@code users.currency} when the request omits it (§6 "Net worth") — there is no literal
     * default anywhere (§9 risk 5).
     */
    @Transactional
    public Asset create(UUID householdId, UUID userId, AssetRequest request) {
        CurrencyCode currency = request.currency() != null ? request.currency()
            : requireCreatingUser(householdId, userId).getCurrency();
        UUID accountId = requireAccountInHousehold(householdId, request.accountId());
        Asset asset = new Asset(householdId, userId, request.name(), request.assetType(),
            currency.normalise(request.value()), currency, accountId, Instant.now());
        return assets.save(asset);
    }

    /**
     * Updates an asset. Full replace of the mutable fields — {@code currency} is never writable
     * here (see {@link AssetUpdateRequest}'s javadoc).
     */
    @Transactional
    public Asset update(UUID householdId, UUID assetId, AssetUpdateRequest request) {
        Asset asset = assets.findByIdAndHouseholdId(assetId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Asset", assetId));
        UUID accountId = requireAccountInHousehold(householdId, request.accountId());
        asset.setName(request.name());
        asset.setAssetType(request.assetType());
        asset.setValue(asset.getCurrency().normalise(request.value()));
        asset.setAccountId(accountId);
        return assets.save(asset);
    }

    @Transactional
    public void delete(UUID householdId, UUID assetId) {
        long removed = assets.deleteByIdAndHouseholdId(assetId, householdId);
        if (removed == 0) {
            throw ResourceNotFoundException.of("Asset", assetId);
        }
    }

    private User requireCreatingUser(UUID householdId, UUID userId) {
        return users.findByIdAndHouseholdId(userId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
    }

    /**
     * {@code null} passes through unchanged (a plain manual entry). A non-null id must name a
     * {@code connected_account} in THIS household — one from another household, or one that does
     * not exist, is a 400 {@code validation-error} rather than a silently stored dangling
     * reference (§6 "Net worth"): the FK alone would only catch a truly nonexistent id, not one
     * that belongs to somebody else's household.
     */
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
