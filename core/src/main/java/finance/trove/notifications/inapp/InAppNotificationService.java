package finance.trove.notifications.inapp;

import finance.trove.common.domain.PagedResult;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.time.Instants;
import finance.trove.notifications.NotificationType;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member's inbox: the reads behind the five {@code /v1/notifications} routes, and the write
 * {@code NotificationService} makes on every notification (§4.9, §6 D3).
 *
 * <p><strong>Ownership is a query parameter, never a check.</strong> Every method here resolves
 * through {@code findByIdAndUserId}, so an id belonging to another member — a household mate
 * included — simply matches nothing and becomes a 404. There is no branch anywhere in this class
 * that compares a row's owner with the caller and answers 403; a 403 would confirm the row exists,
 * which over enough probes is an enumeration oracle.
 */
@Service
public class InAppNotificationService {

    /** The largest page {@code GET /v1/notifications} will serve; mirrors the spec's LimitQuery. */
    public static final int MAX_LIMIT = 200;

    /** The default page size when the caller names none; mirrors the spec's LimitQuery default. */
    public static final int DEFAULT_LIMIT = 50;

    private final InAppNotificationRepository notifications;
    private final Clock clock;

    /** The constructor Spring uses; the class has two, so the container needs to be told. */
    @Autowired
    public InAppNotificationService(InAppNotificationRepository notifications) {
        this(notifications, Clock.systemUTC());
    }

    /** Test seam: a fixed clock makes the {@code readAt}/{@code dismissedAt} stamps assertable. */
    public InAppNotificationService(InAppNotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /** One page of this member's undismissed notifications, newest first. */
    @Transactional(readOnly = true)
    public PagedResult<InAppNotification> page(UUID userId, int page, int limit) {
        Page<InAppNotification> found =
            notifications.findAllByUserIdAndDismissedFalseOrderByCreatedAtDesc(
                userId, PageRequest.of(page, limit));
        return new PagedResult<>(found.getContent(), found.getTotalElements(), page, limit);
    }

    /** The badge number. */
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notifications.countByUserIdAndReadFalseAndDismissedFalse(userId);
    }

    /**
     * Marks one notification read and returns it.
     *
     * @throws ResourceNotFoundException if the id is not this member's — which is also the answer
     *                                   for a household mate's row
     */
    @Transactional
    public InAppNotification markRead(UUID userId, UUID notificationId) {
        InAppNotification notification = ownedOrNotFound(userId, notificationId);
        if (notification.markRead(now())) {
            notifications.save(notification);
        }
        return notification;
    }

    /** Marks every unread, undismissed row read. @return how many changed */
    @Transactional
    public int markAllRead(UUID userId) {
        return notifications.markAllRead(userId, now());
    }

    /**
     * Hides one notification from the inbox. Idempotent.
     *
     * @throws ResourceNotFoundException if the id is not this member's
     */
    @Transactional
    public void dismiss(UUID userId, UUID notificationId) {
        InAppNotification notification = ownedOrNotFound(userId, notificationId);
        if (notification.dismiss(now())) {
            notifications.save(notification);
        }
    }

    /**
     * Writes one inbox row. Called by {@code NotificationService} on every notification, before
     * any channel is attempted and whatever the preferences say — the row is the record, and the
     * absence of one is the defect this whole area was rebuilt to fix.
     *
     * @param announce whether the row should reach the unread badge. False for a member who has
     *                 turned in-app announcements off: the record is still kept, it simply arrives
     *                 already read
     */
    @Transactional
    public InAppNotification record(UUID userId, NotificationType type, String title, String body,
                                    String actionUrl, boolean announce) {
        InAppNotification notification =
            new InAppNotification(userId, type, title, body, actionUrl);
        if (!announce) {
            notification.markDeliveredSilently(now());
        }
        return notifications.save(notification);
    }

    /** Truncated to what the column stores; see {@link Instants}. */
    private Instant now() {
        return Instants.truncate(clock.instant());
    }

    private InAppNotification ownedOrNotFound(UUID userId, UUID notificationId) {
        return notifications.findByIdAndUserId(notificationId, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Notification", notificationId));
    }
}
