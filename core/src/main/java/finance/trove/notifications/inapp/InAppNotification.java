package finance.trove.notifications.inapp;

import finance.trove.notifications.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of a member's notification inbox (schema V33; §2.8, §6 D3).
 *
 * <p><strong>This table is the reason collapse step 11 exists at all.</strong> Finding D3's second
 * half: the pre-collapse service exposed five read endpoints over it and had no writer anywhere,
 * so the notification centre was permanently empty on every install. {@code NotificationService}
 * writes one row here on every notification, unconditionally and before any channel is attempted,
 * which is what makes the inbox the durable record of what this server told a member even when
 * every channel failed.
 *
 * <p>User-scoped, not household-scoped (§1.5): the ledger is shared, an inbox is not.
 *
 * <p>{@code createdAt} is left to the database default rather than set here. It is what the
 * listing orders by, and a clock read on the application side would let two rows written in one
 * transaction disagree with the order they were inserted in.
 */
@Entity
@Table(name = "in_app_notification")
public class InAppNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private NotificationType type;

    @Column(name = "title", nullable = false, length = 300, updatable = false)
    private String title;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "action_url", updatable = false)
    private String actionUrl;

    @Column(name = "read", nullable = false)
    private boolean read;

    @Column(name = "dismissed", nullable = false)
    private boolean dismissed;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "dismissed_at")
    private Instant dismissedAt;

    protected InAppNotification() {
        // JPA
    }

    public InAppNotification(UUID userId, NotificationType type, String title, String body,
                             String actionUrl) {
        this.userId = userId;
        this.type = type;
        this.title = title;
        this.body = body;
        this.actionUrl = actionUrl;
    }

    /**
     * Marks the row read, idempotently.
     *
     * <p>Re-marking keeps the ORIGINAL {@code readAt}: the field answers "when did they first see
     * this", and refreshing it on every repeat call would make it answer "when did the client last
     * retry", which is a different and less useful question.
     *
     * @return whether this call changed anything
     */
    public boolean markRead(Instant at) {
        if (read) {
            return false;
        }
        this.read = true;
        this.readAt = at;
        return true;
    }

    /**
     * Delivers the row already read, for a member who has turned in-app announcements off.
     *
     * <p>The row is still written — losing it would re-create finding D3 — but it never reaches
     * the unread badge. "Do not announce this" and "do not keep a record of it" are different
     * requests, and only the first is one a preference should be able to make.
     */
    public void markDeliveredSilently(Instant at) {
        this.read = true;
        this.readAt = at;
    }

    /**
     * Hides the row from the inbox, idempotently. The row itself is retained: it is the record of
     * what this server told this member, and a member must not be able to erase it by clicking.
     *
     * @return whether this call changed anything
     */
    public boolean dismiss(Instant at) {
        if (dismissed) {
            return false;
        }
        this.dismissed = true;
        this.dismissedAt = at;
        if (!read) {
            this.read = true;
            this.readAt = at;
        }
        return true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getActionUrl() {
        return actionUrl;
    }

    public boolean isRead() {
        return read;
    }

    public boolean isDismissed() {
        return dismissed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getDismissedAt() {
        return dismissedAt;
    }
}
