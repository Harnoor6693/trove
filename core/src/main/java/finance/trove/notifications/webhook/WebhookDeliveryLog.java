package finance.trove.notifications.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One event queued for one subscription (schema V34; §6 D3).
 *
 * <p>No {@code user_id} column and no user-scoped repository: this row is machinery, it is reached
 * only through its subscription — whose {@code user_id} is the scoping column — and no API
 * operation returns it. {@link WebhookDeliveryStore} reads and writes it directly rather than
 * through a Spring Data interface, because the claim step is
 * {@code SELECT ... FOR UPDATE SKIP LOCKED} and that is not a derived query.
 */
@Entity
@Table(name = "webhook_delivery_log")
public class WebhookDeliveryLog {

    /** Five attempts, then {@link Status#EXHAUSTED}. The source's number, kept. */
    public static final int MAX_ATTEMPTS = 5;

    /** The states V34's CHECK admits. */
    public enum Status {
        /** Queued, never attempted. */
        PENDING,
        /**
         * Claimed by a retry pass whose outbound call has not returned.
         *
         * <p>NEW in the collapse. It is what makes the three-transaction {@code deliver()} shape
         * safe: the claim commits this status before the HTTP call starts, so a second pass — or a
         * second instance — cannot pick the same row up while its call is in flight. A row left
         * here by a process that died is deliberately not retried: re-POSTing an event somebody's
         * endpoint may already have acted on is worse than not retrying it.
         */
        IN_FLIGHT,
        /** The endpoint answered 2xx. */
        DELIVERED,
        /** The attempt failed and another is due at {@code nextAttemptAt}. */
        FAILED,
        /** {@link #MAX_ATTEMPTS} attempts failed, or the subscription is gone. No more attempts. */
        EXHAUSTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Column(name = "event_type", nullable = false, length = 50, updatable = false)
    private String eventType;

    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    private Status status = Status.PENDING;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WebhookDeliveryLog() {
        // JPA
    }

    public WebhookDeliveryLog(UUID subscriptionId, String eventType, String payload,
                              Instant dueAt) {
        this.subscriptionId = subscriptionId;
        this.eventType = eventType;
        this.payload = payload;
        this.nextAttemptAt = dueAt;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * Exponential backoff: {@code 2^attempt} seconds, the source's rule kept.
     *
     * <p>After the first failure the next attempt is 2 seconds away, then 4, 8 and 16 — so five
     * attempts span half a minute, which is right for a receiver that is restarting and honest
     * about the fact that this queue is not a durable delivery guarantee.
     */
    public static java.time.Duration backoff(int attempt) {
        return java.time.Duration.ofSeconds(1L << Math.min(attempt, 16));
    }

    /** Records a 2xx. */
    public void delivered(int statusCode, Instant at) {
        this.attemptCount++;
        this.status = Status.DELIVERED;
        this.lastStatusCode = statusCode;
        this.errorMessage = null;
        this.nextAttemptAt = null;
        this.updatedAt = at;
    }

    /**
     * Records a failed attempt and schedules the next one, or gives up.
     *
     * @param statusCode the HTTP status, or null when the call never got one
     * @param reason     why it failed; never null, so an EXHAUSTED row always says what happened
     */
    public void attemptFailed(Integer statusCode, String reason, Instant at) {
        this.attemptCount++;
        this.lastStatusCode = statusCode;
        this.errorMessage = reason;
        this.updatedAt = at;
        if (attemptCount >= MAX_ATTEMPTS) {
            this.status = Status.EXHAUSTED;
            this.nextAttemptAt = null;
        } else {
            this.status = Status.FAILED;
            this.nextAttemptAt = at.plus(backoff(attemptCount));
        }
    }

    /** Gives up without spending an attempt — the subscription is gone or was refused outright. */
    public void abandon(String reason, Instant at) {
        this.status = Status.EXHAUSTED;
        this.errorMessage = reason;
        this.nextAttemptAt = null;
        this.updatedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
