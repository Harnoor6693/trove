package finance.trove.subscriptions.detection;

import finance.trove.common.events.TransactionsIngestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Runs detection for a household as soon as an import or a sync has written its transactions
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D2/M0).
 *
 * <p>Without it, a member who imports a year of history sees an empty subscriptions screen until
 * the nightly job runs — the one moment they have most reason to look at it. With it, the rows
 * are there when the import finishes.
 *
 * <h2>Why the two annotations are both load-bearing</h2>
 * <ul>
 *   <li>{@code AFTER_COMMIT}: the publisher registers this event on the synchronisation manager
 *       from {@code afterCommit} of the batch that persisted the rows, so by the time this runs
 *       the transactions are durable. A listener that ran inside the publishing transaction would
 *       either read rows that a rollback then took away, or be rolled back with them.</li>
 *   <li>{@code REQUIRES_NEW}: after {@code AFTER_COMMIT} the publishing transaction is finished.
 *       A listener that simply called a {@code @Transactional} service would run with no
 *       transaction at all and its writes would be silently discarded — the exact bug
 *       {@code common.events}' package documentation calls out.</li>
 * </ul>
 *
 * <p><strong>Safe to fire several times during one import</strong>, because
 * {@link SubscriptionDetectionService#detectForHousehold} is idempotent: a batch that publishes
 * per account, or a member who imports three files in a row, produces three scans and one set of
 * rows. That is the same property that lets this listener run beside the nightly job.
 *
 * <p>Failures are logged and swallowed. This is an after-commit listener: the transactions are
 * already in the ledger, and an exception here cannot un-write them — it would only propagate out
 * of the commit path of an operation that has, from the member's point of view, already succeeded.
 * The nightly scan picks up whatever this run missed.
 */
@Component
public class TransactionsIngestedSubscriptionListener {

    private static final Logger LOG =
        LoggerFactory.getLogger(TransactionsIngestedSubscriptionListener.class);

    private final SubscriptionDetectionService detection;

    public TransactionsIngestedSubscriptionListener(SubscriptionDetectionService detection) {
        this.detection = detection;
    }

    /** Scans the household whose ledger just changed. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTransactionsIngested(TransactionsIngestedEvent event) {
        if (event.isEmpty()) {
            // A no-op sync changed nothing, so there is nothing new to detect.
            return;
        }
        try {
            detection.detectForHousehold(event.householdId());
        } catch (RuntimeException e) {
            LOG.error("subscription detection after ingest failed for household {}: {}",
                event.householdId(), e.getMessage(), e);
        }
    }
}
