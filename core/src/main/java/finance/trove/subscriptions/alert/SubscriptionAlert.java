package finance.trove.subscriptions.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One anomaly raised against a detected subscription (V30 {@code subscription_alert}).
 *
 * <p><strong>Tenancy.</strong> {@link #getHouseholdId()} is the scope; the source had
 * {@code user_id} only. Under a shared ledger an alert about the household's subscription belongs
 * to the household: either member may read it and either may acknowledge it.
 * {@link #getUserId()} is created-by, for audit.
 *
 * <p>Alerts are never deleted by any route. {@link #isRead()} is how one leaves the unread feed;
 * the row itself is the durable record that the price changed, and it outlives the {@code AMBER}
 * signal on the subscription. It disappears only with its subscription, through the FK cascade —
 * and no route in this version deletes one, because {@code DELETE /v1/subscriptions/{id}} is a
 * soft delete.
 */
@Entity
@Table(name = "subscription_alert")
public class SubscriptionAlert {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(name = "subscription_id", nullable = false, updatable = false)
    private UUID subscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 20, updatable = false)
    private AlertType alertType;

    @Column(name = "alert_message", nullable = false, updatable = false)
    private String alertMessage;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SubscriptionAlert() {
        // JPA
    }

    public SubscriptionAlert(UUID householdId, UUID userId, UUID subscriptionId,
                             AlertType alertType, String alertMessage, Instant createdAt) {
        this.householdId = householdId;
        this.userId = userId;
        this.subscriptionId = subscriptionId;
        this.alertType = alertType;
        this.alertMessage = alertMessage;
        this.read = false;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    /** The owning household — the scope, and the same for every member. */
    public UUID getHouseholdId() {
        return householdId;
    }

    /** Who the detection run that raised this was attributed to. Audit only. */
    public UUID getUserId() {
        return userId;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public AlertType getAlertType() {
        return alertType;
    }

    /** A sentence written for the member, naming the merchant and both amounts. */
    public String getAlertMessage() {
        return alertMessage;
    }

    public boolean isRead() {
        return read;
    }

    public void markRead() {
        this.read = true;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
