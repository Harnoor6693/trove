package finance.trove.notifications.webhook;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two short transactions the split {@code deliver()} needs, and nothing else
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D3).
 *
 * <h2>Why a class and not a Spring Data interface</h2>
 * Two reasons, and both are load-bearing. {@code webhook_delivery_log} has no {@code user_id}: the
 * row is reached only through its subscription, whose {@code user_id} is the scoping column, so
 * none of the three scoped repository bases fits it and ArchUnit would refuse an interface that
 * extended the bare {@code Repository} marker. And the claim is
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, which is not a derived query and would be a native
 * {@code @Query} on an interface anyway.
 *
 * <h2>Why the claim is its own transaction</h2>
 * The source wrapped the whole outbound HTTP call in one {@code @Transactional}, pinning a
 * database connection for up to twenty seconds per delivery and, on a batch of slow receivers,
 * outlasting the retry job's own {@code lockAtMostFor}. Here the claim commits {@code IN_FLIGHT}
 * before any socket is opened, the call happens outside every transaction, and the outcome is
 * written in a second short one. {@code REQUIRES_NEW} on both, because the retry job is not
 * transactional and must not become so by accident.
 */
@Component
public class WebhookDeliveryStore {

    private final EntityManager entityManager;

    public WebhookDeliveryStore(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /** Queues one event for one subscription. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WebhookDeliveryLog enqueue(UUID subscriptionId, String eventType, String payload,
                                      Instant dueAt) {
        WebhookDeliveryLog row =
            new WebhookDeliveryLog(subscriptionId, eventType, payload, dueAt);
        entityManager.persist(row);
        return row;
    }

    /**
     * Claims up to {@code batchSize} due deliveries, marking them {@code IN_FLIGHT}.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} rather than a plain read: two instances of the retry job
     * run the same query at the same second, and without it both would read the same rows, both
     * would POST, and the receiver would see every event twice. {@code SKIP LOCKED} makes the
     * second instance take the next rows instead of blocking on the first's.
     *
     * <p>The rows come back DETACHED, and that is deliberate: the caller is about to spend up to
     * twenty seconds per row on somebody else's network, and a managed entity would keep this
     * transaction's persistence context alive for the whole of it.
     *
     * @return the claimed rows, in due order; empty when nothing is due
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<WebhookDeliveryLog> claimDue(Instant now, int batchSize) {
        @SuppressWarnings("unchecked")
        List<UUID> ids = entityManager.createNativeQuery(
                "SELECT id FROM webhook_delivery_log "
                    + "WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= :now "
                    + "ORDER BY next_attempt_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED")
            .setParameter("now", now)
            .setParameter("batchSize", batchSize)
            .getResultList();
        if (ids.isEmpty()) {
            return List.of();
        }

        List<WebhookDeliveryLog> claimed = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            WebhookDeliveryLog row = entityManager.find(WebhookDeliveryLog.class, id);
            if (row == null) {
                continue;
            }
            entityManager.createNativeQuery(
                    "UPDATE webhook_delivery_log SET status = 'IN_FLIGHT', updated_at = :now "
                        + "WHERE id = :id")
                .setParameter("now", now)
                .setParameter("id", id)
                .executeUpdate();
            entityManager.detach(row);
            claimed.add(row);
        }
        entityManager.clear();
        return claimed;
    }

    /**
     * Writes the outcome of one attempt, in a transaction of its own.
     *
     * <p>A targeted {@code UPDATE ... WHERE id = ?} rather than {@code merge}, and the difference
     * is not stylistic. The row handed in is DETACHED and up to twenty seconds old, and the
     * subscription it belongs to may have been deleted in the meantime — {@code DELETE} on
     * {@code webhook_subscription} cascades to this table, which is the point: a member who
     * revokes an endpoint should not have events arriving at it for the next half minute. A
     * {@code merge} on a row that is no longer there does not fail, it RE-INSERTS it, resurrecting
     * a delivery the member had just cancelled and then failing the foreign key. An UPDATE simply
     * affects nothing.
     *
     * <p>It also writes exactly the five columns an attempt owns. Everything else on the row is
     * immutable after insert, so nothing here can overwrite state that changed while the call was
     * in flight.
     *
     * @return whether the row was still there
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean persistOutcome(WebhookDeliveryLog row) {
        int updated = entityManager.createNativeQuery(
                "UPDATE webhook_delivery_log SET status = :status, attempt_count = :attempts, "
                    + "next_attempt_at = :nextAttemptAt, last_status_code = :lastStatusCode, "
                    + "error_message = :errorMessage, updated_at = :updatedAt WHERE id = :id")
            .setParameter("status", row.getStatus().name())
            .setParameter("attempts", row.getAttemptCount())
            .setParameter("nextAttemptAt", row.getNextAttemptAt())
            .setParameter("lastStatusCode", row.getLastStatusCode())
            .setParameter("errorMessage", row.getErrorMessage())
            .setParameter("updatedAt", row.getUpdatedAt())
            .setParameter("id", row.getId())
            .executeUpdate();
        return updated == 1;
    }

    /** One row by id, for tests and for the retry loop's re-read. Detached. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public WebhookDeliveryLog find(UUID id) {
        WebhookDeliveryLog row = entityManager.find(WebhookDeliveryLog.class, id);
        if (row != null) {
            entityManager.detach(row);
        }
        return row;
    }
}
