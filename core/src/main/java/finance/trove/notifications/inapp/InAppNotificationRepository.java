package finance.trove.notifications.inapp;

import finance.trove.common.repository.UserOwnedRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's inbox ({@code in_app_notification}, V33).
 *
 * <p>Extends {@link UserOwnedRepository} because the rows have a surrogate id and belong to one
 * user. Every finder here names a user, and the base deliberately inherits no {@code findById} —
 * the by-id routes resolve through {@code findByIdAndUserId}, so a household mate asking for
 * another member's notification gets an empty {@code Optional} and therefore a 404 rather than
 * that member's row. {@code ArchitectureTest} fails the build on a repository that extends none of
 * the three scoped bases.
 */
public interface InAppNotificationRepository
    extends UserOwnedRepository<InAppNotification, UUID> {

    /**
     * One page of this member's undismissed notifications, newest first — exactly the query V33's
     * {@code in_app_notification_active_idx} partial index covers.
     */
    Page<InAppNotification> findAllByUserIdAndDismissedFalseOrderByCreatedAtDesc(
        UUID userId, Pageable pageable);

    /**
     * The badge number, covered by V33's {@code in_app_notification_unread_idx}.
     *
     * <p>Mobile polls this on every foreground and background fetch, because the owner's decision
     * is that there is no push channel to tell it otherwise — so it is worth an index of its own.
     */
    long countByUserIdAndReadFalseAndDismissedFalse(UUID userId);

    /**
     * Marks every one of this member's unread, undismissed rows read in one statement.
     *
     * <p>A bulk update rather than a read-then-save loop: the inbox of a member who has been away
     * can be hundreds of rows, and loading them all to flip a boolean would be a page of garbage
     * for no gain. {@code clearAutomatically} because the persistence context may already hold
     * rows this statement just changed behind its back — the controller returns a count rather
     * than the rows, but a caller in the same transaction must not read a stale one.
     *
     * @return how many rows changed; the number the client decrements its badge by
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update InAppNotification n set n.read = true, n.readAt = :now "
        + "where n.userId = :userId and n.read = false and n.dismissed = false")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);
}
