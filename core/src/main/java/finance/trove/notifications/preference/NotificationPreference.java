package finance.trove.notifications.preference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One member's notification settings (schema V31; {@code docs/recovery/06-COLLAPSE-PLAN.md} §2.8,
 * §3, §1.5).
 *
 * <p><strong>The user id is the primary key and there is no household id.</strong> Every ledger
 * entity in this application is scoped by household because the owner's decision is one shared
 * ledger; this one is the deliberate opposite, for the same reason {@code ai_settings} is. A
 * surrogate id would be a way to address this row without naming its owner, which is the shape of
 * the IDOR §9 risk 2 exists to prevent — so there is not one, and
 * {@link NotificationPreferenceRepository} extends {@code UserKeyedRepository}.
 *
 * <p><strong>No push field exists on this class, and that is a decision.</strong> The source
 * carried {@code pushEnabled}, {@code apnsToken} and {@code fcmToken}; all three go with the
 * owner's 2026-09-03 decision (no push until a direct APNs/FCM path exists, no Expo relay).
 * {@code taxReminderEnabled} goes with the tax engine (owner decision 2, defect D-N3). Neither is
 * in V31, in the response DTO, or in the spec.
 *
 * <p>The threshold is a whole percent rather than the source's {@code NUMERIC(3,2)} ratio: an
 * integer percent is not a numeric column at all, so it needs no entry in
 * {@code SchemaDefaultsIT.NON_MONEY_NUMERICS} and cannot become a double by accident. See V31.
 */
@Entity
@Table(name = "notification_preference")
public class NotificationPreference {

    /** The shipped default: tell me at 90 % of the envelope. Matches V31's column default. */
    public static final short DEFAULT_BUDGET_ALERT_THRESHOLD_PERCENT = 90;

    /** The lowest threshold V31's CHECK admits. */
    public static final short MIN_THRESHOLD_PERCENT = 1;

    /** The highest, which in practice is an opt-out. */
    public static final short MAX_THRESHOLD_PERCENT = 200;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "in_app_enabled", nullable = false)
    private boolean inAppEnabled = true;

    /**
     * Off by default, unlike the source. An install with no SMTP configured cannot deliver email,
     * and opting every member in to a channel that silently drops everything is the defect D3 is
     * about.
     */
    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled;

    @Column(name = "email_address", length = 320)
    private String emailAddress;

    @Column(name = "weekly_digest_enabled", nullable = false)
    private boolean weeklyDigestEnabled = true;

    @Column(name = "subscription_alert_enabled", nullable = false)
    private boolean subscriptionAlertEnabled = true;

    @Column(name = "sync_failure_alert_enabled", nullable = false)
    private boolean syncFailureAlertEnabled = true;

    @Column(name = "budget_alert_threshold_percent", nullable = false)
    private short budgetAlertThresholdPercent = DEFAULT_BUDGET_ALERT_THRESHOLD_PERCENT;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationPreference() {
        // JPA
    }

    public NotificationPreference(UUID userId) {
        this.userId = userId;
    }

    /**
     * The row a member who has never saved anything gets. It is PERSISTED on first read rather
     * than returned transient, because the digest enumerates recipients from this table: a member
     * with no row would silently never be in it, which is how the pre-collapse digest managed to
     * report success with nothing sent.
     */
    public static NotificationPreference defaultsFor(UUID userId) {
        return new NotificationPreference(userId);
    }

    /**
     * Both callbacks, because the column is NOT NULL and a freshly constructed defaults row
     * carries no timestamp: without the insert half the first save would fail the constraint.
     */
    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * This member's threshold as the fraction {@code EnvelopeSpendService} compares against, e.g.
     * {@code 0.90} for 90 %. Two decimal places exactly, so two members holding the same percent
     * produce values that compare equal in a {@code Set}.
     */
    public BigDecimal thresholdFraction() {
        return BigDecimal.valueOf(budgetAlertThresholdPercent).divide(ONE_HUNDRED, 2,
            java.math.RoundingMode.UNNECESSARY);
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isInAppEnabled() {
        return inAppEnabled;
    }

    public void setInAppEnabled(boolean inAppEnabled) {
        this.inAppEnabled = inAppEnabled;
    }

    public boolean isEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
    }

    public boolean isWeeklyDigestEnabled() {
        return weeklyDigestEnabled;
    }

    public void setWeeklyDigestEnabled(boolean weeklyDigestEnabled) {
        this.weeklyDigestEnabled = weeklyDigestEnabled;
    }

    public boolean isSubscriptionAlertEnabled() {
        return subscriptionAlertEnabled;
    }

    public void setSubscriptionAlertEnabled(boolean subscriptionAlertEnabled) {
        this.subscriptionAlertEnabled = subscriptionAlertEnabled;
    }

    public boolean isSyncFailureAlertEnabled() {
        return syncFailureAlertEnabled;
    }

    public void setSyncFailureAlertEnabled(boolean syncFailureAlertEnabled) {
        this.syncFailureAlertEnabled = syncFailureAlertEnabled;
    }

    public short getBudgetAlertThresholdPercent() {
        return budgetAlertThresholdPercent;
    }

    /**
     * @throws IllegalArgumentException outside V31's CHECK range, so a bad value fails in the
     *         service layer with a message rather than as a constraint violation at flush
     */
    public void setBudgetAlertThresholdPercent(short percent) {
        if (percent < MIN_THRESHOLD_PERCENT || percent > MAX_THRESHOLD_PERCENT) {
            throw new IllegalArgumentException("budgetAlertThresholdPercent must be between "
                + MIN_THRESHOLD_PERCENT + " and " + MAX_THRESHOLD_PERCENT + ", got " + percent);
        }
        this.budgetAlertThresholdPercent = percent;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
