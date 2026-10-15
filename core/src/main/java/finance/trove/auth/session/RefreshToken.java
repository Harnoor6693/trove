package finance.trove.auth.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One link in a refresh-token chain (schema V7).
 *
 * <p>A <em>family</em> is every token descended from one login. Rotation on each use means a
 * stolen token stops working as soon as the real client refreshes once — and, more usefully, that
 * the theft becomes <em>detectable</em>, because the stale token eventually comes back and no
 * honest client would ever send one. That detection revokes the family (see
 * {@link RefreshTokenService}).
 *
 * <p>{@code expiresAt} is the family's absolute deadline and is copied unchanged into every
 * successor, so rotating forever cannot extend a session past the ceiling §1.5 sets.
 * {@code lastUsedAt} carries the independent idle timeout.
 *
 * <p>{@code tokenHash} is SHA-256 hex of the token; the token itself is never stored.
 */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, length = 128, updatable = false)
    private String tokenHash;

    @Column(name = "family_id", nullable = false, updatable = false)
    private UUID familyId;

    @Column(name = "issued_at", nullable = false, updatable = false, insertable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    protected RefreshToken() {
        // JPA
    }

    public RefreshToken(UUID userId, String tokenHash, UUID familyId, Instant expiresAt,
                        Instant lastUsedAt, String ipAddress, String userAgent) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.lastUsedAt = lastUsedAt;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
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

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getIssuedAt() {
        return issuedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getReplacedBy() {
        return replacedBy;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    /** Marks this link as rotated: revoked, and pointing at the successor that replaced it. */
    public void replaceWith(UUID successorId, Instant now) {
        this.replacedBy = successorId;
        this.revokedAt = now;
    }

    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
    }

    public boolean isRotated() {
        return replacedBy != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
