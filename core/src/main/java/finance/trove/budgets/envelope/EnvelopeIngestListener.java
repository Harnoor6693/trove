package finance.trove.budgets.envelope;

import finance.trove.common.events.TransactionsIngestedEvent;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Moves envelopes when a sync or an import writes transactions
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, M0 → D1).
 *
 * <h2>Why this listener exists at all</h2>
 * The plan specifies one listener for D1, on {@code TransactionCategorisedEvent}. That predates
 * the merged code, and on the merged code alone it would leave the defect standing with a new
 * cause. {@code TransactionService} is the <em>only</em> publisher of that event in the tree
 * today — the {@code PATCH} path — while ingested rows get their {@code category_id} from
 * {@code TransactionCategoriser} at INSERT with no event at all; that class's own javadoc says the
 * ingest-time event and its listener are collapse step 16's. A budget wired only to the categorise
 * event would therefore sit at zero through an entire freshly synced month, which is finding D1
 * again wearing a different hat.
 *
 * <p>So this listener consumes {@link TransactionsIngestedEvent} as well, resolves the affected
 * {@code (category, month)} pairs from the ids it carries, and funnels into the same
 * {@link EnvelopeSpendService} call. <strong>Because the aggregate is a rewrite rather than an
 * increment, the overlap costs nothing:</strong> when step 16's {@code CategorisationListener}
 * starts publishing a categorised event per ingested row, each row will be recomputed twice and
 * produce the same number both times. Step 16 may drop whichever of the two it supersedes; until
 * then dropping either one loses months of budget movement.
 *
 * <p>Updated ids matter as much as new ones: a pending transaction that posts, or one whose amount
 * a source corrected, changes what its envelope should say without being new to the ledger.
 *
 * <p>{@code AFTER_COMMIT} + {@code REQUIRES_NEW} and non-propagating failures, for the same
 * reasons {@link EnvelopeSpendListener} states.
 */
@Component
public class EnvelopeIngestListener {

    private static final Logger LOG = LoggerFactory.getLogger(EnvelopeIngestListener.class);

    private final EnvelopeSpendService spend;
    private final TransactionQueryService transactions;

    public EnvelopeIngestListener(EnvelopeSpendService spend,
                                  TransactionQueryService transactions) {
        this.spend = spend;
        this.transactions = transactions;
    }

    /** Recomputes every envelope a sync or import could have moved. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTransactionsIngested(TransactionsIngestedEvent event) {
        if (event.isEmpty()) {
            return;
        }

        List<UUID> ids = new ArrayList<>(event.newTransactionIds());
        ids.addAll(event.updatedTransactionIds());

        try {
            Set<CategoryMonth> targets = new LinkedHashSet<>();
            for (Transaction row : transactions.findAllByIds(event.householdId(), ids)) {
                if (row.getCategoryId() == null) {
                    // Uncategorised: no envelope budgets it. Step 16's categorisation listener
                    // publishes a categorised event when that changes, and this envelope is
                    // recomputed then.
                    continue;
                }
                targets.add(new CategoryMonth(row.getCategoryId(),
                    YearMonth.from(row.getDate())));
            }
            if (targets.isEmpty()) {
                return;
            }
            spend.recalculateAll(event.householdId(), event.userId(), targets);
        } catch (RuntimeException e) {
            LOG.error("Envelope spend recalculation failed after ingestion of {} rows into "
                + "household {}; the aggregates are stale until the next write to them",
                ids.size(), event.householdId(), e);
        }
    }
}
