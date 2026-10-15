package finance.trove.subscriptions.detection;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which households a scan should visit ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D2;
 * {@code docs/recovery/01-FINDINGS.md} D2 / inventory SD-1).
 *
 * <h2>The bug this class exists to not repeat</h2>
 * <p>The pre-collapse scheduler enumerated its work with
 * {@code SubscriptionRepository.findDistinctActiveUserIds()} —
 * {@code SELECT DISTINCT s.userId FROM DetectedSubscription s WHERE s.cancelled = false}. On a
 * fresh install {@code detected_subscription} is empty, so that query returned nobody, so
 * {@code detectForUser} was called on nobody, so no row was ever written, so the query was still
 * empty the next night. <strong>The feature could never detect its first subscription on any
 * install</strong>, and the nightly job genuinely ran, so it was a live defect rather than a
 * dormant one. Moving that query across unchanged would have relocated a dead feature into the
 * monolith.
 *
 * <p>The useful set is the opposite one: households that have TRANSACTIONS, whether or not they
 * have any detections yet. A household with no transactions has nothing to detect and costs one
 * row of a {@code DISTINCT} scan to skip.
 *
 * <h2>Why the query lives here and not on TransactionQueryService</h2>
 * <p>{@code TransactionQueryService} is the shared read seam and would be the natural home, but it
 * belongs to collapse step 6 and steps 8, 9 and 10 of this wave run in parallel worktrees — a new
 * method there is a merge conflict nobody can see until the branches meet. It is one statement
 * over one column and it moves onto that seam later; the step's merge notes say so.
 */
@Component
public class SubscriptionScanTargets {

    private static final String HOUSEHOLDS_WITH_ACTIVITY = """
        SELECT DISTINCT household_id
        FROM transaction
        WHERE date >= CAST(:since AS date)
        """;

    private final EntityManager entityManager;

    public SubscriptionScanTargets(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Households with at least one transaction dated on or after {@code since}.
     *
     * @param since the earliest date worth looking at — the scan's own window, widened by the
     *              caller so that no household is missed because of a timezone difference
     * @return household ids, in no particular order
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<UUID> householdsWithActivitySince(LocalDate since) {
        return entityManager.createNativeQuery(HOUSEHOLDS_WITH_ACTIVITY, UUID.class)
            .setParameter("since", since)
            .getResultList();
    }
}
