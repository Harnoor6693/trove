package finance.trove.notifications.webhook;

import finance.trove.common.time.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One member's outbound webhook (schema V34; §2.8, §6 D3).
 *
 * <p>User-scoped, not household-scoped (§1.5): the URL is one member's and the signing secret is
 * one member's.
 *
 * <p><strong>{@code secretEncrypted} is ciphertext, and the field name says so.</strong> The
 * source stored the shared HMAC secret as plaintext {@code VARCHAR(128)} — a working credential
 * sitting in the clear in every database backup. It goes through {@code FieldEncryptionService}
 * with its {@code v1:} prefix, exactly as step 3 encrypts invite tokens and step 12 the per-user
 * AI key. Nothing on this class exposes it decrypted; {@link WebhookDeliveryService} is the only
 * caller that decrypts, and it hands the result straight to the MAC.
 */
@Entity
@Table(name = "webhook_subscription")
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @Column(name = "secret_encrypted", nullable = false)
    private String secretEncrypted;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "event_types", columnDefinition = "text[]", nullable = false)
    private List<String> eventTypes = List.of();

    /**
     * Written by the application rather than left to V34's {@code DEFAULT now()}.
     *
     * <p>{@code POST /v1/webhooks} answers 201 with the row it just saved, and a column marked
     * {@code insertable = false} is null on that instance until somebody re-reads it — so the
     * create response would carry {@code "createdAt": null} against a schema that requires a
     * timestamp. The default stays on the column for rows inserted by anything other than this
     * entity.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instants.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instants.now();

    protected WebhookSubscription() {
        // JPA
    }

    public WebhookSubscription(UUID userId, String targetUrl, String secretEncrypted,
                               List<String> eventTypes) {
        this.userId = userId;
        this.targetUrl = targetUrl;
        this.secretEncrypted = secretEncrypted;
        this.eventTypes = eventTypes == null ? List.of() : List.copyOf(eventTypes);
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instants.now();
    }

    /** Whether this subscription wants {@code eventType}. An empty list means all of them. */
    public boolean wants(String eventType) {
        return eventTypes.isEmpty() || eventTypes.contains(eventType);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    /** The CIPHERTEXT. Never returned by any API operation and never logged. */
    public String getSecretEncrypted() {
        return secretEncrypted;
    }

    public boolean isEnabled() {
        return enabled;
    }
    public List<String> getEventTypes() {
        return eventTypes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
