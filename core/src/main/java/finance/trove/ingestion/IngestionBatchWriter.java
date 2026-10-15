package finance.trove.ingestion;

import finance.trove.accounts.ConnectedAccount;
import finance.trove.common.events.TransactionsIngestedEvent;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.ingestion.dedup.DedupBatch;
import finance.trove.ingestion.dedup.DeduplicationResult;
import finance.trove.ingestion.dedup.DeduplicationService;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionCategoriser;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Persists one run's accepted rows, in <strong>one short transaction</strong>
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 M0).
 *
 * <p><strong>Why this is a bean of its own, and why the sync method is not transactional.</strong>
 * If the whole sync were one transaction, a single row failing partway through would mark it
 * rollback-only — and the {@code PARTIAL_FAILURE} log that exists precisely to record that would
 * then throw {@code UnexpectedRollbackException}. A partial failure could never be recorded. So
 * the fetch happens outside any transaction, the log rows are written in their own, and this
 * class holds the only transaction that touches the ledger.
 *
 * <p><strong>A refused row never enters that transaction.</strong> Each row is validated by
 * {@link DeduplicationService} before anything is written, and a
 * {@link RawTransactionRejectedException} is thrown before the persistence context is touched. So
 * counting a failure costs an increment and leaves the batch entirely usable — which is what makes
 * "some rows written, some refused" an outcome that can actually happen rather than a state the
 * transaction manager forbids.
 *
 * <p><strong>The event is published from {@code afterCommit}</strong>, not from inside the
 * transaction. Nothing consumes {@code TransactionsIngestedEvent} in this step — the
 * {@code CategorisationListener} is step 16's and the envelope listener step 8's — but publishing
 * it here is what lets those steps be purely additive. A no-op run publishes nothing:
 * {@link TransactionsIngestedEvent#isEmpty()} is checked first, so a member who syncs an unchanged
 * account does not set off a chain of recomputation and notifications for nothing.
 */
@Component
public class IngestionBatchWriter {

    /** How many distinct rejection reasons are quoted before the summary is truncated. */
    private static final int MAX_REASONS = 5;

    private static final Logger LOG = LoggerFactory.getLogger(IngestionBatchWriter.class);

    private final DeduplicationService deduplication;
    private final TransactionCategoriser categoriser;
    private final ApplicationEventPublisher events;

    public IngestionBatchWriter(DeduplicationService deduplication,
                                TransactionCategoriser categoriser,
                                ApplicationEventPublisher events) {
        this.deduplication = deduplication;
        this.categoriser = categoriser;
        this.events = events;
    }

    /**
     * Reconciles and stores every row the source offered.
     *
     * @param account the account being synced — already resolved inside its household
     * @param userId  the member whose action produced the run, stored as created-by
     * @param rows    what the source returned
     * @param importBatchId the {@code import_batch} this run is committing, stamped onto every row
     *                      it INSERTS; {@code null} for an ordinary bank sync. It is what
     *                      {@code DELETE /v1/imports/{id}} later finds the batch's own rows by, and
     *                      it is set on insert only — {@code transaction.import_batch_id} is
     *                      {@code updatable = false}, because a row's provenance is a fact about
     *                      how it got here and not a field to be re-attributed later
     */
    @Transactional
    public IngestionBatch persist(ConnectedAccount account, UUID userId,
                                  List<RawTransaction> rows, UUID importBatchId) {
        DedupBatch batch = new DedupBatch(rows);
        List<UUID> created = new ArrayList<>();
        List<UUID> updated = new ArrayList<>();
        Set<String> reasons = new LinkedHashSet<>();
        int skipped = 0;
        int failed = 0;

        for (RawTransaction row : rows) {
            try {
                DeduplicationService.Outcome outcome =
                    deduplication.reconcileOne(account, userId, row, batch, importBatchId);
                Transaction stored = outcome.transaction();
                if (outcome.result() == DeduplicationResult.NEW) {
                    categorise(account, stored);
                    created.add(stored.getId());
                } else if (outcome.result() == DeduplicationResult.UPDATED) {
                    updated.add(stored.getId());
                } else {
                    skipped++;
                }
            } catch (RawTransactionRejectedException e) {
                // Thrown before any write, so the batch transaction is still healthy. Nothing
                // about the row itself is logged or stored beyond the reason.
                LOG.warn("Ingestion refused a row on account {}: {}", account.getId(),
                    e.getMessage());
                reasons.add(e.getMessage());
                failed++;
            }
        }

        publishAfterCommit(account, userId, created, updated);
        return new IngestionBatch(created, updated, skipped, failed, summarise(reasons, failed));
    }

    /**
     * Sets {@code transaction.category_id} on a newly inserted row (§6 D4).
     *
     * <p>This is the column nothing wrote before this step: the pre-collapse pipeline resolved a
     * category and discarded the answer, so every budget aggregate downstream had nothing to read.
     * A member's pin is respected first — a re-sync must never quietly reclassify a row somebody
     * has already corrected — even though a freshly inserted row cannot yet carry one, because
     * this method is the place a later caller will reach for and the guard belongs where the write
     * is.
     */
    private void categorise(ConnectedAccount account, Transaction stored) {
        if (categoriser.isPinned(account.getHouseholdId(), stored.getId())) {
            return;
        }
        stored.setCategoryId(categoriser.categoriseOnInsert(account.getHouseholdId(),
            stored.getMerchantName(), stored.getDescription(), stored.getAmount(),
            stored.getCurrency().name()));
    }

    /**
     * Publishes once the rows are actually durable.
     *
     * <p>Registered on the synchronisation manager rather than published inline so that a listener
     * added by a later step cannot observe ids that a rollback then took away. When there is no
     * active transaction — a direct call from a test — it fires immediately, because there is no
     * commit to wait for.
     */
    private void publishAfterCommit(ConnectedAccount account, UUID userId, List<UUID> created,
                                    List<UUID> updated) {
        TransactionsIngestedEvent event = new TransactionsIngestedEvent(account.getHouseholdId(),
            userId, account.getId(), created, updated);
        if (event.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            events.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    events.publishEvent(event);
                }
            });
    }

    /** A short, row-free explanation for {@code sync_log.error_message}. */
    private static String summarise(Set<String> reasons, int failed) {
        if (failed == 0) {
            return null;
        }
        List<String> quoted = reasons.stream().limit(MAX_REASONS).toList();
        String suffix = reasons.size() > MAX_REASONS ? ", and others" : "";
        return failed + " row(s) could not be accepted: " + String.join("; ", quoted) + suffix;
    }
}
