package finance.trove.budgets.envelope;

import finance.trove.common.events.TransactionCategorisedEvent;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Moves envelopes when a transaction's category changes
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, D4 → D1).
 *
 * <p>This is one half of the seam that retires finding D1. {@code TransactionService} publishes
 * {@link TransactionCategorisedEvent} from {@code PATCH /v1/transactions/{id}}; this listener
 * recomputes the aggregate for <strong>both</strong> the previous and the new category, which is
 * exactly why the event carries {@code previousCategoryId} — after the write it is gone, and an
 * envelope the transaction has just left would otherwise keep counting it for ever.
 *
 * <p><strong>{@code AFTER_COMMIT} with its own {@code REQUIRES_NEW} transaction</strong>, per §6's
 * opening paragraph. After commit, because the aggregate must be computed from the state the
 * ledger actually settled on rather than one a rollback could still undo; its own transaction,
 * because there is no longer an open one to join at that point, and a listener that silently did
 * nothing would reproduce D1 with a new cause.
 *
 * <p><strong>Failures do not propagate</strong> to the request that caused them: the write the
 * member asked for has already committed, and turning "the budget did not refresh" into "your
 * recategorisation failed" would be a worse answer to give them. It is logged, and the next
 * recomputation of the same envelope fixes it — which is affordable only because the aggregate is
 * a rewrite rather than an increment.
 */
@Component
public class EnvelopeSpendListener {

    private static final Logger LOG = LoggerFactory.getLogger(EnvelopeSpendListener.class);

    private final EnvelopeSpendService spend;

    public EnvelopeSpendListener(EnvelopeSpendService spend) {
        this.spend = spend;
    }

    /** Recomputes the category the transaction left and the one it joined, in that order. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTransactionCategorised(TransactionCategorisedEvent event) {
        // A LinkedHashSet, so a transaction re-pinned to the category it already had recomputes
        // once rather than twice, and a null (uncategorised) side contributes nothing.
        Set<CategoryMonth> targets = new LinkedHashSet<>();
        if (event.previousCategoryId() != null) {
            targets.add(new CategoryMonth(event.previousCategoryId(), event.month()));
        }
        if (event.categoryId() != null) {
            targets.add(new CategoryMonth(event.categoryId(), event.month()));
        }
        if (targets.isEmpty()) {
            return;
        }

        try {
            spend.recalculateAll(event.householdId(), event.userId(), targets);
        } catch (RuntimeException e) {
            LOG.error("Envelope spend recalculation failed for transaction {} in household {}; "
                + "the aggregate is stale until the next write to it", event.transactionId(),
                event.householdId(), e);
        }
    }
}
