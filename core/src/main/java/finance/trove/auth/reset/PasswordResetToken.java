package finance.trove.auth.reset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A one-hour, single-use password reset link (V9.1, readiness gap P2-8).
 *
 * <p>Only the SHA-256 hash of the token is stored, for the same reason refresh tokens are hashed:
 * a database dump must not yield anything replayable. The raw token exists exactly once, in the
 * response the admin copies.
 *
 * <p>{@code consumedAt} rather than a delete, so that a second click on the same link answers
 * "already used" from evidence rather than from an absence that could equally mean "never
 * existed". The daily sweep removes rows past their expiry.
 */
@Entity
@Table(name = "password_reset_token")
public class PasswordResetToken {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected PasswordResetToken() {
        // JPA
    }

    public PasswordResetToken(UUID userId, String tokenHash, UUID createdBy, Instant expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdBy = createdBy;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    /** Marks the token spent. Idempotent, so a double submit cannot reset twice. */
    public void consume(Instant now) {
        if (this.consumedAt == null) {
            this.consumedAt = now;
        }
    }

    public boolean isUsableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now);
    }
}
