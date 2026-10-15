package finance.trove.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One member's own AI endpoint configuration (schema V16; owner decision 9,
 * {@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V37, §6).
 *
 * <p><strong>The user id is the primary key, and there is no household id.</strong> Every ledger
 * entity in this application is scoped by household because the owner's decision is one shared
 * ledger; this one is the deliberate opposite. The endpoint, the key and the privacy tier are each
 * member's own, and no route — administrative or otherwise — lets one member read another's. A
 * surrogate id would be a way to address this row without naming its owner, which is exactly the
 * shape of the IDOR §9 risk 2 exists to prevent, so there is not one.
 *
 * <p>{@code apiKeyEnc} is AES-GCM ciphertext from {@code FieldEncryptionService}. The plaintext key
 * exists on the heap for the length of one call and is never logged, never returned and never
 * written anywhere else. Nothing on this class exposes it decrypted; {@link AiSettingsService} is
 * the only caller that decrypts, and it hands the result straight to the HTTP client.
 *
 * <p>The timeouts are what the member ASKED for. The effective values are capped at call time
 * against the install's {@code TROVE_HTTP_*} limits, so a stored value can never hold a request
 * thread for longer than the operator allows — see {@link AiTimeouts}.
 */
@Entity
@Table(name = "ai_settings")
public class AiSettings {

    /** Applied to a row that has never been saved, matching the V16 column defaults. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;

    /** 120 s, the figure §5.5 names for AI, because a local model streams slowly. */
    public static final int DEFAULT_READ_TIMEOUT_MS = 120_000;

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "endpoint_url")
    private String endpointUrl;

    @Column(name = "api_key_enc")
    private String apiKeyEnc;

    @Column(name = "model")
    private String model;

    @Column(name = "vision_model")
    private String visionModel;

    @Column(name = "allow_private_endpoint", nullable = false)
    private boolean allowPrivateEndpoint;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy_tier", nullable = false)
    private PrivacyTier privacyTier = PrivacyTier.CLOUD_OK;

    @Column(name = "connect_timeout_ms", nullable = false)
    private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;

    @Column(name = "read_timeout_ms", nullable = false)
    private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

    @Column(name = "last_test_at")
    private Instant lastTestAt;

    @Column(name = "last_test_ok")
    private Boolean lastTestOk;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiSettings() {
        // JPA
    }

    public AiSettings(UUID userId) {
        this.userId = userId;
    }

    /**
     * The row a member who has never saved anything sees.
     *
     * <p>Returned rather than a 404 so that a settings screen renders identically on the first
     * visit and the hundredth, and so that every caller of {@link AiGateway} gets the same
     * "disabled" answer whether the row is absent or present-and-off.
     */
    public static AiSettings defaultsFor(UUID userId) {
        return new AiSettings(userId);
    }

    /**
     * Both callbacks, because the column is NOT NULL and the defaults row carries no timestamp:
     * without the insert half, the first save of a member's settings would fail the constraint.
     */
    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    /** The ciphertext, not the key. There is no accessor that returns a decrypted key. */
    public String getApiKeyEnc() {
        return apiKeyEnc;
    }

    public boolean hasApiKey() {
        return apiKeyEnc != null && !apiKeyEnc.isBlank();
    }

    public String getModel() {
        return model;
    }

    public String getVisionModel() {
        return visionModel;
    }

    public boolean isAllowPrivateEndpoint() {
        return allowPrivateEndpoint;
    }

    public PrivacyTier getPrivacyTier() {
        return privacyTier;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public Instant getLastTestAt() {
        return lastTestAt;
    }

    public Boolean getLastTestOk() {
        return lastTestOk;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Null until the row has been written once — the defaults carry no timestamp. */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Applies a validated replacement. Everything except the key comes from {@code source}; the
     * key has its own two routes because it is write-only and cannot round-trip through a GET.
     */
    public void replaceWith(boolean newEnabled, String newEndpointUrl, String newModel,
                            String newVisionModel, boolean newAllowPrivateEndpoint,
                            PrivacyTier newPrivacyTier, int newConnectTimeoutMs,
                            int newReadTimeoutMs) {
        this.enabled = newEnabled;
        this.endpointUrl = newEndpointUrl;
        this.model = newModel;
        this.visionModel = newVisionModel;
        this.allowPrivateEndpoint = newAllowPrivateEndpoint;
        this.privacyTier = newPrivacyTier;
        this.connectTimeoutMs = newConnectTimeoutMs;
        this.readTimeoutMs = newReadTimeoutMs;
        this.updatedAt = Instant.now();
    }

    /** Stores fresh ciphertext. Passing null leaves the stored key alone. */
    public void setApiKeyEnc(String ciphertext) {
        if (ciphertext != null) {
            this.apiKeyEnc = ciphertext;
            this.updatedAt = Instant.now();
        }
    }

    /** Forgets the stored key; the endpoint and the models stay as they are. */
    public void clearApiKey() {
        this.apiKeyEnc = null;
        this.updatedAt = Instant.now();
    }

    // There is deliberately no setter for lastTestAt/lastTestOk. The connection test reads this
    // row, then waits on somebody else's HTTP server for as long as the member's timeout allows,
    // so the object it holds is a stale detached snapshot by the time it has an outcome; writing
    // it back through save() would merge every column and undo whatever landed in that window.
    // AiSettingsRepository#recordTestOutcome writes those two columns with one statement instead.
}
