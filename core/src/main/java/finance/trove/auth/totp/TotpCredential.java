package finance.trove.auth.totp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One user's second factor (schema V5; gap P2-6).
 *
 * <p>{@code secretEncrypted} is AES-GCM ciphertext from {@code FieldEncryptionService}, never the
 * base32 secret: a database dump of this table must not be a set of working authenticators.
 *
 * <p>{@code enabled} stays false until one live code has been verified. That verify-before-enable
 * step is the difference between "TOTP is on" and "TOTP is on and the user can actually produce a
 * code" — a user who mis-scans the QR and enables anyway is locked out of a finance server with no
 * self-service way back.
 *
 * <p>{@code recoveryCodesHashed} holds SHA-256 hashes; a code is consumed by removing its hash, so
 * single-use is a property of the row rather than of a flag someone must remember to set.
 *
 * <p>{@code lastUsedStep} is the replay guard: the same six digits are accepted once, even though
 * they stay numerically valid for the rest of their thirty-second window.
 *
 * <p>Both are spent by {@link TotpCredentialRepository#claimStep} and
 * {@link TotpCredentialRepository#consumeRecoveryCode}, never through this entity: those are the
 * only writes that stay single-use when two logins present the same code at once.
 */
@Entity
@Table(name = "totp_credential")
public class TotpCredential {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "secret_encrypted", nullable = false)
    private String secretEncrypted;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "recovery_codes_hashed", nullable = false, columnDefinition = "text[]")
    private String[] recoveryCodesHashed = new String[0];

    @Column(name = "last_used_step")
    private Long lastUsedStep;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected TotpCredential() {
        // JPA
    }

    public TotpCredential(UUID userId, String secretEncrypted) {
        this.userId = userId;
        this.secretEncrypted = secretEncrypted;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSecretEncrypted() {
        return secretEncrypted;
    }

    /** Restarting enrolment replaces the secret and drops any confirmation and codes. */
    public void resetSecret(String secretEncrypted) {
        this.secretEncrypted = secretEncrypted;
        this.enabled = false;
        this.confirmedAt = null;
        this.lastUsedStep = null;
        this.recoveryCodesHashed = new String[0];
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void enable(Instant confirmedAt, List<String> hashedRecoveryCodes) {
        this.enabled = true;
        this.confirmedAt = confirmedAt;
        this.recoveryCodesHashed = hashedRecoveryCodes.toArray(String[]::new);
    }

    public List<String> getRecoveryCodesHashed() {
        return recoveryCodesHashed == null ? List.of() : List.of(recoveryCodesHashed);
    }

    public int remainingRecoveryCodes() {
        return recoveryCodesHashed == null ? 0 : recoveryCodesHashed.length;
    }

    public long getLastUsedStep() {
        return lastUsedStep == null ? -1L : lastUsedStep;
    }

    public void setLastUsedStep(long step) {
        this.lastUsedStep = step;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
