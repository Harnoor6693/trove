package finance.trove.transactions;

import finance.trove.common.repository.UserScopedRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository over {@link CategoryOverride}.
 *
 * <p>Scoped like every other ledger repository. The lookup is by {@code transactionId} within a
 * household rather than by transaction alone, so a pin can never be read or replaced across a
 * tenancy boundary even though {@code transaction_id} is unique on its own.
 */
public interface CategoryOverrideRepository extends UserScopedRepository<CategoryOverride, UUID> {

    Optional<CategoryOverride> findByHouseholdIdAndTransactionId(UUID householdId,
                                                                 UUID transactionId);

    /** {@code true} when a member has pinned this transaction, so ingestion must not recategorise. */
    boolean existsByHouseholdIdAndTransactionId(UUID householdId, UUID transactionId);
}
