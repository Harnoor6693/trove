package finance.trove.households.invites;

import finance.trove.users.UserRole;
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
 * A single-use, time-boxed invitation to join one household (schema V9.1; gap P2-7).
 *
 * <p>Registration is closed by default, because an open {@code /register} on the public internet
 * lets a stranger create an account on somebody's finance server. An invite is how a household
 * admin opens exactly one door, for 72 hours, once.
 *
 * <p><strong>No SMTP required.</strong> The admin copies a URL. That is the whole delivery
 * mechanism, and it is the reason the feature works on the majority of self-hosted installs,
 * which have no mail server (P2-7, P2-8).
 *
 * <p>The SHA-256 hash is the only lookup key: redemption never reads the ciphertext. Alongside it
 * (V9.2) the raw token is kept AES-GCM-encrypted under {@code TROVE_ENCRYPTION_KEY} so that the
 * admin listing can render the link — the web admin UI reads {@code url} from the listing and
 * discards the creation response ({@code apps/web/src/pages/Settings/index.tsx:166-169, 215}), so
 * without it the feature has no usable link. The ciphertext is cleared the moment the invite is
 * accepted or revoked, and is never decrypted for an expired one; a database dump alone still
 * yields no working invitation, because the key is in the environment.
 */
@Entity
@Table(name = "household_invite")
public class HouseholdInvite {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "email", length = 320)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private UserRole role = UserRole.MEMBER;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    /**
     * AES-GCM ciphertext of the raw token (V9.2), or {@code null} once the invite can no longer
     * be used. Never a credential the server accepts — {@link #tokenHash} is what redemption
     * looks the row up by.
     */
    @Column(name = "token_encrypted")
    private String tokenEncrypted;

    @Column(name = "created_by")
    private UUID createdBy;

    /**
     * Set by the application clock, from the same {@code now} that fixes {@link #expiresAt}.
     *
     * <p>It used to be {@code insertable = false}, left entirely to the column's
     * {@code DEFAULT now()}. That meant Hibernate inserted the row and left this field null on
     * the entity it had just persisted, so {@code POST /v1/admin/invites} answered
     * {@code "createdAt": null} for an invitation that had unmistakably just been created —
     * while {@code GET /v1/admin/invites}, which reads the row back, returned a real timestamp
     * for the same invite. A client generated from {@code openapi/trove-core.yaml} types the
     * field as a non-null string, so the creation response handed it a value it cannot render.
     * Found by the step-3b conformance check.
     *
     * <p>Writing it from {@code now} rather than reading the database default back also makes
     * the invitation's two timestamps agree: {@code expiresAt} was already
     * {@code now.plus(72h)} from the application clock, so a database clock even slightly out of
     * step made the advertised 72-hour window not 72 hours. The column keeps its
     * {@code DEFAULT now()} for any row inserted outside JPA.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected HouseholdInvite() {
        // JPA
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public HouseholdInvite(UUID householdId, String email, UserRole role, String tokenHash,
                           String tokenEncrypted, UUID createdBy, Instant createdAt,
                           Instant expiresAt) {
        this.householdId = householdId;
        this.email = email;
        this.role = role;
        this.tokenHash = tokenHash;
        this.tokenEncrypted = tokenEncrypted;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public String getEmail() {
        return email;
    }

    public UserRole getRole() {
        return role;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getTokenEncrypted() {
        return tokenEncrypted;
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

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public UUID getAcceptedUserId() {
        return acceptedUserId;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    /** Redeems the invite and forgets the readable copy of its token — it is spent. */
    public void accept(UUID userId, Instant now) {
        this.acceptedAt = now;
        this.acceptedUserId = userId;
        this.tokenEncrypted = null;
    }

    /** Withdraws the invite and forgets the readable copy of its token — it is dead. */
    public void revoke(Instant now) {
        if (this.revokedAt == null) {
            this.revokedAt = now;
        }
        this.tokenEncrypted = null;
    }

    /** Usable exactly once, before it expires, until an admin withdraws it. */
    public boolean isUsableAt(Instant now) {
        return acceptedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}
