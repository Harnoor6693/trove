package finance.trove.ingestion.dedup;

import finance.trove.common.plugin.RawTransaction;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * What one ingestion run already knows about itself, so the fuzzy pass cannot collapse two
 * distinct transactions into one ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 M0).
 *
 * <p>Two things are tracked, and each closes half of the same hole:
 * <ul>
 *   <li><strong>The provider ids this run is carrying.</strong> A stored row whose own
 *       {@code provider_transaction_id} appears among them is a row this very batch is about to
 *       reconcile through the exact-match path, so it must not also be offered to the fuzzy pass
 *       as somebody else's near-match.</li>
 *   <li><strong>The rows this run has already matched.</strong> Without it, two id-less incoming
 *       transactions of the same amount on the same day would both fuzzy-match the same stored
 *       row and the second would be recorded as a duplicate of a transaction it has nothing to do
 *       with.</li>
 * </ul>
 *
 * <p>Not thread-safe, and does not need to be: one batch belongs to one sync run on one thread,
 * and two concurrent runs against one account are prevented by the {@code SYNCING} claim rather
 * than by locking here.
 */
public final class DedupBatch {

    private final Set<String> incomingProviderIds;
    private final Set<UUID> matchedRowIds = new HashSet<>();

    public DedupBatch(List<RawTransaction> incoming) {
        this.incomingProviderIds = new HashSet<>();
        for (RawTransaction row : incoming) {
            if (row.hasProviderId()) {
                incomingProviderIds.add(row.providerTransactionId());
            }
        }
    }

    /** An empty batch, for a caller with a single row and no run context. */
    public static DedupBatch of(RawTransaction... rows) {
        return new DedupBatch(List.of(rows));
    }

    /** Whether this run is itself carrying a row with that provider id. */
    public boolean carriesProviderId(String providerTransactionId) {
        return providerTransactionId != null && incomingProviderIds.contains(providerTransactionId);
    }

    /** Whether this run has already matched the stored row with that id. */
    public boolean hasMatched(UUID rowId) {
        return matchedRowIds.contains(rowId);
    }

    /** Records that a stored row has been claimed by an incoming one. */
    public void markMatched(UUID rowId) {
        matchedRowIds.add(rowId);
    }
}
