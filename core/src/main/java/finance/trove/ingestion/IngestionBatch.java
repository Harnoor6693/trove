package finance.trove.ingestion;

import java.util.List;
import java.util.UUID;

/**
 * What one batch of incoming rows did to the ledger.
 *
 * @param newTransactionIds     rows inserted
 * @param updatedTransactionIds rows changed in place — a pending transaction that has posted
 * @param skipped               rows already present and unchanged
 * @param failed                rows refused before any write; non-zero means PARTIAL_FAILURE
 * @param failureReason         a summary of why rows were refused; {@code null} when none were.
 *                              It quotes the reasons, never the rows: a transaction description is
 *                              a household's spending history and this string is stored and shown
 */
public record IngestionBatch(List<UUID> newTransactionIds, List<UUID> updatedTransactionIds,
                             int skipped, int failed, String failureReason) {

    public IngestionBatch {
        newTransactionIds = List.copyOf(newTransactionIds);
        updatedTransactionIds = List.copyOf(updatedTransactionIds);
    }

    /** Whether any row was refused, which is what makes a run PARTIAL_FAILURE rather than SUCCESS. */
    public boolean hasFailures() {
        return failed > 0;
    }

    public int created() {
        return newTransactionIds.size();
    }

    public int updated() {
        return updatedTransactionIds.size();
    }
}
