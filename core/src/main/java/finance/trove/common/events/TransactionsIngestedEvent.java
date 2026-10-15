package finance.trove.common.events;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A sync or import finished writing transactions to an account
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, M0/D4).
 *
 * <p>Published by {@code SyncOrchestrator} from {@code afterCommit} of the batch that persisted
 * the rows, and consumed by {@code CategorisationListener}, which is the only thing that ever
 * writes {@code transaction.category_id} — the column nothing writes today.
 *
 * <p>New and updated ids are separate lists because they mean different things downstream: a new
 * row needs categorising, an updated one (a pending transaction that posted) only needs its
 * envelope aggregate recomputed.
 *
 * @param householdId           the ledger these transactions belong to
 * @param userId                the member whose sync or import produced them
 * @param accountId             the {@code connected_account} they were written against
 * @param newTransactionIds     rows inserted by this run
 * @param updatedTransactionIds rows this run modified in place
 */
public record TransactionsIngestedEvent(UUID householdId, UUID userId, UUID accountId,
                                        List<UUID> newTransactionIds,
                                        List<UUID> updatedTransactionIds)
    implements HouseholdEvent {

    public TransactionsIngestedEvent {
        Objects.requireNonNull(householdId, "householdId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(accountId, "accountId cannot be null");
        newTransactionIds = List.copyOf(newTransactionIds);
        updatedTransactionIds = List.copyOf(updatedTransactionIds);
    }

    /** Whether this run changed anything at all; a no-op sync publishes nothing downstream. */
    public boolean isEmpty() {
        return newTransactionIds.isEmpty() && updatedTransactionIds.isEmpty();
    }
}
