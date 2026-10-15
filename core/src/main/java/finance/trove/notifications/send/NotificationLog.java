package finance.trove.notifications.send;

import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One delivery attempt, with its real outcome (schema V32; finding D3).
 *
 * <p>Append-mostly: a row is inserted {@code PENDING} and rewritten once, when the channel
 * answers. {@code PENDING} lasts microseconds inside one transaction here — it is not a queue
 * anything polls, which is why V32 does not recreate the source's dead
 * {@code idx_notification_log_pending} index.
 *
 * <p><strong>The id is assigned in Java, not by the database.</strong> The claim insert is a native
 * statement with {@code ON CONFLICT DO NOTHING} — the only way to make the deduplication decision
 * atomic — and a statement that inserts nothing returns nothing, so the caller has to know the id
 * it would have written before it writes it. See
 * {@link NotificationLogRepository#claim}.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLog {

    /** The states V32's CHECK admits. */
    public enum Status {
        /** Claimed, not yet answered for. Lives inside one transaction. */
        PENDING,
        /** The channel delivered. */
        SENT,
        /** The channel did not; {@code errorMessage} is NOT NULL, enforced by V32. */
        FAILED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 12, updatable = false)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40, updatable = false)
    private NotificationType type;

    @Column(name = "subject", length = 500, updatable = false)
    private String subject;

    @Column(name = "body", updatable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 8)
    private Status status = Status.PENDING;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "dedup_key", length = 200, updatable = false)
    private String dedupKey;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected NotificationLog() {
        // JPA
    }

    public NotificationLog(UUID id, UUID userId, NotificationChannel channel,
                           NotificationType type, String subject, String body, String dedupKey) {
        this.id = id;
        this.userId = userId;
        this.channel = channel;
        this.type = type;
        this.subject = subject;
        this.body = body;
        this.dedupKey = dedupKey;
    }

    /**
     * Records what the channel actually did.
     *
     * <p>The status and the reason move together and only together: there is no setter that can
     * leave {@code FAILED} without a message, which is the row shape finding D3 produced on every
     * install.
     */
    public void record(DeliveryResult result, Instant at) {
        if (result.isSent()) {
            this.status = Status.SENT;
            this.errorMessage = null;
            this.sentAt = at;
        } else {
            this.status = Status.FAILED;
            this.errorMessage = result.error();
            this.sentAt = null;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationType getType() {
        return type;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
