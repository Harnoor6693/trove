package finance.trove.auth.local;

import finance.trove.common.time.Instants;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One user's password material and account-lockout state (schema V4; gaps P2-4, P2-5).
 *
 * <p>{@code passwordHash} is a complete argon2id PHC string, so the cost parameters travel with
 * the hash and raising them later re-hashes on next login rather than locking everyone out.
 *
 * <p>{@code failedAttempts} and {@code lockedUntil} are the account side of the brute-force
 * control. They live on the credential rather than on {@code users} because a user who signs in
 * only through OIDC has no password to brute-force and therefore no row here at all — which is
 * the shape that makes "this account cannot be locked out by guessing" expressible rather than
 * merely true.
 */
@Entity
@Table(name = "local_credential")
public class LocalCredential {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "password_updated_at", nullable = false)
    private Instant passwordUpdatedAt = Instants.now();

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    protected LocalCredential() {
        // JPA
    }

    public LocalCredential(UUID userId, String passwordHash, Instant now) {
        this.userId = userId;
        this.passwordHash = passwordHash;
        this.passwordUpdatedAt = now;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    /** Replaces the hash and resets the lockout state — a successful reset clears the lock. */
    public void setPasswordHash(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.passwordUpdatedAt = now;
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    /** Re-hash after a cost-parameter change: same password, new encoding, no lockout reset. */
    public void rehash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public Instant getPasswordUpdatedAt() {
        return passwordUpdatedAt;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void recordFailure(int attempts, Instant lockedUntil) {
        this.failedAttempts = attempts;
        this.lockedUntil = lockedUntil;
    }

    public void clearLock() {
        this.failedAttempts = 0;
        this.lockedUntil = null;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }
}
