package finance.trove.ingestion.importbatch;

import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.ingestion.ImportMapping;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionCategoriser;
import finance.trove.transactions.TransactionRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short transactions an import needs around its non-transactional parts
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-12 "then undo").
 *
 * <p>A bean of its own for the same reason {@code SyncStateWriter} is: {@code ImportService} is
 * deliberately not transactional — it makes a full sync call — and Spring's
 * {@code @Transactional} is applied by a proxy, so a self-invocation from inside that class would
 * run with no transaction at all, silently.
 *
 * <h2>What "untouched" means, and what it deliberately does not cover</h2>
 * An undo removes the rows THIS batch inserted <strong>and that no member has since edited</strong>.
 * The tree can observe four of a member's edits, and {@link #isUntouched} uses all four:
 * <ul>
 *   <li>{@code manual} — a hand-entered row was never this batch's to remove;</li>
 *   <li>{@code notes} — only {@code PATCH /v1/transactions/{id}} ever sets one;</li>
 *   <li>{@code tags} — likewise;</li>
 *   <li>a {@code category_override} row — the member's pinned category, which is also the signal
 *       {@code TransactionCategoriser} uses to stop a re-sync reclassifying a corrected row.</li>
 * </ul>
 * <p><strong>A merchant rename on its own is not covered</strong>, and that is a known limitation
 * rather than an oversight: {@code merchant_name} carries no record of having been changed, and
 * {@code updated_at} cannot stand in for one — the transfer-pairing job and a pending-to-posted
 * promotion both bump it without a member being involved, so using it would make an undo a silent
 * no-op for every paired row. A per-row "edited by a member" marker is the fix, and it needs a
 * column this step is not allowed to add.
 *
 * <p><strong>Never a blanket delete by {@code import_batch_id}.</strong> V22 chose
 * {@code ON DELETE SET NULL} on that column precisely so that removing a batch can never take a
 * household's ledger with it; the undo makes the same distinction, one row at a time, through the
 * household-scoped delete. A row that is kept keeps the batch id too — it is the record of where
 * the row came from, and the batch's {@code UNDONE} status is what says the rest were removed.
 */
@Component
public class ImportUndoService {

    private static final Logger LOG = LoggerFactory.getLogger(ImportUndoService.class);

    private final ImportBatchRepository batches;
    private final TransactionRepository transactions;
    private final TransactionCategoriser categoriser;

    public ImportUndoService(ImportBatchRepository batches, TransactionRepository transactions,
                             TransactionCategoriser categoriser) {
        this.batches = batches;
        this.transactions = transactions;
        this.categoriser = categoriser;
    }

    /**
     * Removes this batch's untouched rows and marks it {@code UNDONE}.
     *
     * @return how many rows were removed
     */
    @Transactional
    public int undo(UUID householdId, UUID batchId) {
        ImportBatch batch = require(householdId, batchId);
        List<Transaction> rows =
            transactions.findAllByHouseholdIdAndImportBatchIdOrderByCreatedAtAsc(householdId,
                batchId);
        int removed = 0;
        for (Transaction row : rows) {
            if (!isUntouched(householdId, row)) {
                continue;
            }
            removed += (int) transactions.deleteByIdAndHouseholdId(row.getId(), householdId);
        }
        batch.markUndone();
        batches.save(batch);
        if (removed < rows.size()) {
            LOG.info("Import batch {} left {} edited row(s) in place", batchId,
                rows.size() - removed);
        }
        return removed;
    }

    /** Records what the commit did. See {@link ImportBatch#markCommitted}. */
    @Transactional
    public void markCommitted(UUID householdId, UUID batchId, int rowCount, int newCount,
                              int dupCount) {
        ImportBatch batch = require(householdId, batchId);
        batch.markCommitted(rowCount, newCount, dupCount);
        batches.save(batch);
    }

    /** Persists the mapping a commit is about to use, so the batch always says what it used. */
    @Transactional
    public void storeMapping(UUID householdId, UUID batchId, ImportMapping mapping) {
        ImportBatch batch = require(householdId, batchId);
        batch.setMappingJson(mapping);
        batches.save(batch);
    }

    /** See the class javadoc. */
    private boolean isUntouched(UUID householdId, Transaction row) {
        return !row.isManual()
            && row.getNotes() == null
            && (row.getTags() == null || row.getTags().isEmpty())
            && !categoriser.isPinned(householdId, row.getId());
    }

    private ImportBatch require(UUID householdId, UUID batchId) {
        return batches.findByIdAndHouseholdId(batchId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Import", batchId));
    }
}
