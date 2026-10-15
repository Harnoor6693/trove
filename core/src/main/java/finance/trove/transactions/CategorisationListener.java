package finance.trove.transactions;

import finance.trove.categorisation.BatchCategoriser;
import finance.trove.common.events.TransactionsIngestedEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The seam that categorises a freshly ingested batch and tells the rest of the tree about it
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D4; collapse step 16, §7 row 16).
 *
 * <p>{@code TransactionsIngestedEvent}'s own javadoc names this class, and so does
 * {@link TransactionCategoriser}'s: it is the consumer both of them were written for.
 *
 * <h2>What it owns, and what it deliberately leaves alone</h2>
 * It owns the <em>second</em>, owner-aware pass over the rows a sync or an import just inserted.
 * {@code IngestionBatchWriter} already set {@code category_id} at INSERT from the free, offline
 * tiers — with no owner, and therefore without ever consulting anybody's AI, which is exactly
 * right inside that write transaction. This listener runs after that transaction has committed,
 * asks {@link BatchCategoriser} the same question <em>with</em> the row owner named, and writes
 * back whatever that resolves that the offline pass could not.
 *
 * <p>It handles {@link TransactionsIngestedEvent#newTransactionIds()} only. That is the event
 * record's own distinction — "a new row needs categorising, an updated one (a pending transaction
 * that posted) only needs its envelope aggregate recomputed" — and re-running categorisation over
 * a row that has been in the ledger for a month is a backfill, which is step 17's PROPOSED
 * {@code POST /v1/categorise/backfill} and not this step's.
 *
 * <h2>A pin wins</h2>
 * A row a member has corrected through {@code PATCH /v1/transactions/{id}} is dropped from the
 * batch before {@link BatchCategoriser} ever sees it, through the existing
 * {@link TransactionCategoriser#isPinned} check rather than a second implementation of it. It is
 * not merely left unwritten: it is not asked about, so a pinned row cannot cost an AI call either.
 *
 * <h2>The transactional shape is the point of this class</h2>
 * The method below is {@code @TransactionalEventListener(AFTER_COMMIT)} and carries <strong>no
 * {@code @Transactional}</strong>. That is not an omission. Step 17's {@code BatchCategoriser} is
 * deliberately not transactional and opens its own short transactions per call; calling it from
 * inside a write transaction would pin one pooled connection for the whole batch of model
 * round-trips — §6 D3's defect, moved to a new place. So the answers are gathered with nothing
 * held, and {@link CategorisationWriteBack} then opens the one short {@code REQUIRES_NEW}
 * transaction that writes the column and publishes the events.
 *
 * <p>That split is guarded, not merely documented. {@code CategorisationSeamIT#theSplitIsWhatSpring
 * ItselfResolves} asserts against the container's own {@code TransactionAttributeSource} that this
 * method resolves to NO transaction attribute and that {@code CategorisationWriteBack#apply}
 * resolves to {@code REQUIRES_NEW}; {@code CategorisationSeamIT#theRoundTripHappensOutsideTheWrite
 * Transaction} runs the method under the container's own {@code TransactionInterceptor} and fails
 * if a transaction is held when {@link BatchCategoriser} is entered; and {@code
 * CategorisationListenerTest#theAnnotationContractIsTheHazardControl} repeats both cheaply without
 * a container. A {@code @Transactional} tidied onto the method below turns all three red.
 *
 * <h2>A failure here never reaches the member</h2>
 * The rows are already committed. If categorisation fails, the ledger is intact and the rows keep
 * whatever the offline pass gave them; the failure is recorded as an ERROR log carrying the
 * household, the account and the row count — never a merchant string and never a member's
 * description — and the next sync of the same account categorises the next batch normally.
 *
 * <h2>Why {@code EnvelopeIngestListener} is NOT superseded by this class</h2>
 * Step 8 left the choice open ("step 16 may drop whichever of the two it supersedes"). It is kept,
 * and the reason is the paragraph above about updated ids: {@code EnvelopeIngestListener} is the
 * only thing that recomputes an envelope for a row this listener does not categorise — a pending
 * transaction that posted, or one whose amount the source corrected, changes what an envelope
 * should say without changing any category. The overlap on NEW rows is free, because
 * {@code EnvelopeSpendService} rewrites the aggregate from the ledger rather than incrementing it
 * (§9 risk 4), so recomputing twice produces the same number twice.
 */
@Component
public class CategorisationListener {

    private static final Logger LOG = LoggerFactory.getLogger(CategorisationListener.class);

    private final TransactionQueryService transactions;
    private final TransactionCategoriser categoriser;
    private final BatchCategoriser batch;
    private final CategorisationWriteBack writeBack;

    public CategorisationListener(TransactionQueryService transactions,
                                  TransactionCategoriser categoriser, BatchCategoriser batch,
                                  CategorisationWriteBack writeBack) {
        this.transactions = transactions;
        this.categoriser = categoriser;
        this.batch = batch;
        this.writeBack = writeBack;
    }

    /**
     * Categorises the rows a sync or an import just inserted.
     *
     * <p>{@code AFTER_COMMIT}: acting on rows a rollback could still take away would write a
     * category onto a transaction that does not exist and publish an event about it.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionsIngested(TransactionsIngestedEvent event) {
        List<UUID> newIds = event.newTransactionIds();
        if (newIds.isEmpty()) {
            return;
        }
        try {
            List<BatchCategoriser.Item> items = itemsFor(event, newIds);
            if (items.isEmpty()) {
                return;
            }
            // Outside every transaction: see the class javadoc. The write opens its own.
            BatchCategoriser.Outcome outcome =
                batch.categorise(event.householdId(), event.userId(), items);
            int changed = writeBack.apply(event, outcome.results());
            report(event, items.size(), changed, outcome);
        } catch (RuntimeException e) {
            LOG.error("Ingest categorisation failed for {} new rows on account {} in household "
                    + "{}; the rows keep the category the ingest pass gave them", newIds.size(),
                event.accountId(), event.householdId(), e);
        }
    }

    /**
     * The batch, with pinned rows removed.
     *
     * <p>The read is {@link TransactionQueryService#findAllByIds}, which is household-scoped and
     * runs in its own short read-only transaction — this method holds none.
     */
    private List<BatchCategoriser.Item> itemsFor(TransactionsIngestedEvent event,
                                                 List<UUID> newIds) {
        List<BatchCategoriser.Item> items = new ArrayList<>();
        for (Transaction row : transactions.findAllByIds(event.householdId(), newIds)) {
            if (categoriser.isPinned(event.householdId(), row.getId())) {
                continue;
            }
            items.add(new BatchCategoriser.Item(row.getId(), row.getMerchantName(),
                row.getDescription(), row.getAmount(), row.getCurrency().name()));
        }
        return items;
    }

    /**
     * One operator-facing line per batch, carrying the counts and no member content.
     *
     * <p>{@code leftUncategorisedAtCap} is the number step 17 asked to be made visible: it is how
     * many rows are still {@code Uncategorised} because the per-batch AI ceiling ran out rather
     * than because no tier knew the merchant. It is logged at WARN when it is non-zero, because it
     * is the signal that an import is converging over several syncs instead of finishing in one,
     * and at DEBUG otherwise so an ordinary batch stays quiet.
     */
    private static void report(TransactionsIngestedEvent event, int considered, int changed,
                               BatchCategoriser.Outcome outcome) {
        if (outcome.leftUncategorisedAtCap() > 0) {
            LOG.warn("Ingest categorisation for household {} account {}: {} rows considered, {} "
                    + "recategorised, {} AI calls attempted, {} rows left uncategorised at the "
                    + "per-batch cap", event.householdId(), event.accountId(), considered, changed,
                outcome.aiCallsAttempted(), outcome.leftUncategorisedAtCap());
            return;
        }
        LOG.debug("Ingest categorisation for household {} account {}: {} rows considered, {} "
                + "recategorised, {} AI calls attempted", event.householdId(), event.accountId(),
            considered, changed, outcome.aiCallsAttempted());
    }
}
