package finance.trove.subscriptions.subscription;

import finance.trove.common.repository.UserScopedRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository over {@link DetectedSubscription} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.7,
 * §9 risk 2).
 *
 * <p>Extends {@link UserScopedRepository}, so it inherits no {@code findById} and no
 * {@code findAll}: every query takes a {@code householdId}, which is what makes a cross-household
 * read a compile error rather than a review question. {@code ArchitectureTest} additionally fails
 * the build if a subinterface declares an unscoped finder by hand.
 *
 * <p><strong>Detection does not write through this interface.</strong> The upsert is
 * {@code INSERT ... ON CONFLICT (household_id, merchant_name) DO UPDATE} issued by
 * {@code SubscriptionDetectionService} (§6 D2), because a JPA read-then-write is the
 * duplicate-insert race V29's unique constraint exists to close (inventory SD-5). What lives here
 * is the read model and the soft delete.
 */
public interface SubscriptionRepository extends UserScopedRepository<DetectedSubscription, UUID> {

    /**
     * The household's active subscriptions, alphabetically. Cancelled rows are excluded; a
     * member who dismissed one does not want to see it again.
     *
     * <p>Served by V29's partial index {@code detected_subscription_household_active_idx}, which
     * covers the filter and the ordering together.
     */
    List<DetectedSubscription> findAllByHouseholdIdAndCancelledFalseOrderByMerchantNameAsc(
        UUID householdId);

    /**
     * The SOFT delete behind {@code DELETE /v1/subscriptions/{id}} — it sets the flag, and the
     * row survives.
     *
     * <p>A single UPDATE rather than a load-mutate-save so that dismissing a subscription cannot
     * lose a race with a scan that is upserting the same row: this statement touches two columns
     * and the scan's upsert never touches {@code is_cancelled}, so whichever order they commit in,
     * both writes survive. It is also idempotent — dismissing an already-cancelled subscription
     * still matches its row and still returns 1, so the route answers 200 rather than 404.
     *
     * <p>{@code @Transactional} is not optional: Spring Data runs a repository query method in a
     * read-only transaction unless told otherwise, and Postgres refuses an UPDATE in one.
     *
     * @return 1 when the row belongs to this household, 0 when it does not exist or belongs to
     *         another — which the caller answers as a 404, never a 403 (§9 risk 2)
     */
    @Transactional
    @Modifying
    @Query("update DetectedSubscription s set s.cancelled = true, s.updatedAt = :now "
        + "where s.id = :id and s.householdId = :householdId")
    int cancelByIdAndHouseholdId(@Param("id") UUID id, @Param("householdId") UUID householdId,
                                 @Param("now") Instant now);
}
