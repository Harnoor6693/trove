package finance.trove.users;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * One member of one household ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V3).
 *
 * <p>{@code currency}, {@code country}, {@code timezone} and {@code locale} are identity-level
 * fields on this row rather than preferences behind a second table, for two different reasons.
 * The first pair is read directly by five other packages and is assumed stable by every stored
 * transaction, budget and export — changing a reporting currency after the fact silently
 * reinterprets history, which is why they are writable only while onboarding is still in
 * progress. The second pair decides where this user's "today" begins ({@link #getTimezone()} is
 * what {@code UserClock} resolves through) and how amounts are formatted, and both change freely.
 *
 * <p>{@code onboardingCompleted} is here, not in {@code user_preferences}, because the web
 * {@code AuthGuard} consults it on every route change; it belongs on the row every authenticated
 * request already has in hand.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    private UserRole role;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "country", nullable = false, length = 2)
    private Jurisdiction country;

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    @Column(name = "locale", nullable = false, length = 10)
    private String locale;

    @Column(name = "region", length = 3)
    private String region;

    @Column(name = "onboarding_completed", nullable = false)
    private boolean onboardingCompleted;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * When this account entered the deletion grace, or null (V42, collapse step 18).
     *
     * <p>This column exists for the one thing {@link #active} cannot say: the difference between a
     * member an administrator merely disabled and a member who asked to leave. {@code is_active}
     * is what every liveness check in the tree already reads — sign-in, refresh, the weekly
     * digest, insight recipients, the webhook audience — so the soft delete IS
     * {@code active = false} plus this stamp, and there is deliberately no second liveness flag or
     * status enum that could disagree with the first one. Only a stamped row is ever erased by the
     * purge; a disabled member with a null stamp is never touched by it.
     */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    /** Who deleted this account — the member themselves, or an administrator (V42). */
    @Column(name = "deleted_by")
    private UUID deletedBy;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected User() {
        // JPA
    }

    public User(UUID householdId, UserRole role, String email, String displayName,
                Jurisdiction country, CurrencyCode currency, ZoneId timezone) {
        this.householdId = householdId;
        this.role = role;
        this.email = email;
        this.displayName = displayName;
        this.country = country;
        this.currency = currency;
        this.timezone = timezone.getId();
        this.locale = country.defaultLocaleTag();
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public void setCurrency(CurrencyCode currency) {
        this.currency = currency;
    }

    public Jurisdiction getCountry() {
        return country;
    }

    public void setCountry(Jurisdiction country) {
        this.country = country;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public boolean isOnboardingCompleted() {
        return onboardingCompleted;
    }

    public void setOnboardingCompleted(boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public UUID getDeletedBy() {
        return deletedBy;
    }

    /**
     * Enters the deletion grace: deactivated and stamped, in one call.
     *
     * <p>One method rather than three setters, because the three writes are one fact. A row that
     * is stamped but still active is a member who can sign in while the purge is counting down on
     * them; a row that is inactive but unstamped is a member nobody will ever erase and no
     * administrator can restore, because restore is defined by the stamp.
     */
    public void softDelete(UUID actorId, Instant when) {
        this.active = false;
        this.deletedAt = when;
        this.deletedBy = actorId;
    }

    /**
     * Leaves the deletion grace: reactivated and unstamped.
     *
     * <p>Sessions are deliberately not part of this. They were revoked when the account was
     * deleted and cannot be un-revoked — {@code CookieOrBearerJwtAuthFilter} asks
     * {@code SessionValidity.isLive(fid)} on every authenticated request, so a revoked family
     * really is revoked. The member signs in again with the credentials they already had.
     */
    public void restore() {
        this.active = true;
        this.deletedAt = null;
        this.deletedBy = null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
