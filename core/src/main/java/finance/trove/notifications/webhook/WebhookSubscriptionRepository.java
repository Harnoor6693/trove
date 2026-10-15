package finance.trove.notifications.webhook;

import finance.trove.common.repository.UserOwnedRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@code webhook_subscription} (V34). Extends {@link UserOwnedRepository}, so
 * {@code GET /v1/webhooks} and {@code DELETE /v1/webhooks/{id}} can only reach rows through a
 * finder that names a user: a household mate's subscription is an empty {@code Optional} and
 * therefore a 404.
 */
public interface WebhookSubscriptionRepository
    extends UserOwnedRepository<WebhookSubscription, UUID> {

    List<WebhookSubscription> findAllByUserIdOrderByCreatedAtAsc(UUID userId);

    /**
     * Every enabled subscription of one member that wants some event — the read the seam collapse
     * step 16 calls makes.
     */
    List<WebhookSubscription> findAllByUserIdAndEnabledTrue(UUID userId);

    /**
     * The subscription one queued delivery belongs to, without a user id.
     *
     * <p><strong>The one unscoped lookup here, and it is deliberate.</strong> The retry job has no
     * caller and therefore no user to scope by; it reaches this row only from a
     * {@code webhook_delivery_log} row that was itself created for this subscription, so the
     * subscription id it holds came from this server rather than from a request. It is named
     * {@code findSubscription} rather than {@code findById} for the same reason
     * {@code UserRepository.findAccount} is: {@code findById} is the name
     * {@code ArchitectureTest} bans on a scoped repository, and a method that quietly
     * reintroduced it would hand every later step an unscoped finder to reach for by accident
     * (§9 risk 2). A distinct name makes each use a decision.
     */
    @Query("select s from WebhookSubscription s where s.id = :id")
    Optional<WebhookSubscription> findSubscription(@Param("id") UUID id);

    /**
     * Takes a {@code PESSIMISTIC_WRITE} lock on the OWNER's row before the per-member cap is
     * counted.
     *
     * <p>The cap was a check-then-act: {@code countByUserId} and then an INSERT, at READ
     * COMMITTED, with no lock and no constraint in V34 that bounds a member's row count. N
     * concurrent {@code POST /v1/webhooks} from one member all read the same count and all
     * inserted, so the member ended up holding more outbound endpoints than the cap allows — and
     * the cap is a resource control, not decoration: every subscription is a connection this
     * server makes on that member's behalf, and {@code /api/**} allows 600 requests a minute,
     * which is ample concurrency to beat a bare count.
     *
     * <p>The owner row is the right thing to lock, and it is the same move
     * {@code UserAdminService.deleteMember} makes for the last-ADMIN guard: it locks the tenancy
     * root — there the household row, here the member's own {@code users} row — because it is the
     * one row that certainly exists whatever the count turns out to be. Locking the existing
     * subscriptions instead would not serialise a member who currently has none.
     *
     * <p>Native SQL because there is no JPA lock to take on a row this repository's entity does
     * not own, and it deliberately selects nothing but the id: this is a lock, not a read.
     *
     * @return the member's own id, or empty when the account is gone
     */
    @Query(value = "SELECT id FROM users WHERE id = :userId FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockOwner(@Param("userId") UUID userId);
}
