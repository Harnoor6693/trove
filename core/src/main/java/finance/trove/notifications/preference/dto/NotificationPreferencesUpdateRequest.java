package finance.trove.notifications.preference.dto;

import finance.trove.notifications.preference.NotificationPreference;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code PUT /v1/notifications/preferences} — a PARTIAL update despite the verb
 * (§4.9 pins {@code PUT}, which is what the pre-collapse clients already call).
 *
 * <p><strong>A bean rather than a record, for one reason that no record can express.</strong>
 * Every field is optional, and for every field except one, "absent" and "explicitly null" mean the
 * same thing: leave it alone. {@code emailAddress} is the exception — an explicit
 * {@code "emailAddress": null} is how a member clears a stored address, and there is no other way
 * to say it. Jackson calls a setter for an explicit null and does not call it at all for an absent
 * key, so a setter that records that it ran is the only construction that can tell the two apart;
 * a record's canonical constructor receives {@code null} either way and the distinction is gone
 * before any code sees it.
 *
 * <p><strong>There is no push field to send.</strong> A body carrying {@code pushEnabled},
 * {@code apnsToken} or {@code fcmToken} is accepted and ignored, exactly as any other unknown key
 * is: the server runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled and the spec's request
 * schemas are open for that reason.
 */
public class NotificationPreferencesUpdateRequest {

    private Boolean inAppEnabled;
    private Boolean emailEnabled;

    @Email
    @Size(max = 320)
    private String emailAddress;

    /** Whether the key was present at all — see the class javadoc. */
    private boolean emailAddressPresent;

    private Boolean weeklyDigestEnabled;
    private Boolean subscriptionAlertEnabled;
    private Boolean syncFailureAlertEnabled;

    @Min(NotificationPreference.MIN_THRESHOLD_PERCENT)
    @Max(NotificationPreference.MAX_THRESHOLD_PERCENT)
    private Integer budgetAlertThresholdPercent;

    public Boolean getInAppEnabled() {
        return inAppEnabled;
    }

    public void setInAppEnabled(Boolean inAppEnabled) {
        this.inAppEnabled = inAppEnabled;
    }

    public Boolean getEmailEnabled() {
        return emailEnabled;
    }

    public void setEmailEnabled(Boolean emailEnabled) {
        this.emailEnabled = emailEnabled;
    }

    public String getEmailAddress() {
        return emailAddress;
    }

    /** Records presence as well as value; an explicit {@code null} clears the stored address. */
    public void setEmailAddress(String emailAddress) {
        this.emailAddress = emailAddress;
        this.emailAddressPresent = true;
    }

    /** Whether the caller mentioned {@code emailAddress} at all. */
    public boolean isEmailAddressPresent() {
        return emailAddressPresent;
    }

    public Boolean getWeeklyDigestEnabled() {
        return weeklyDigestEnabled;
    }

    public void setWeeklyDigestEnabled(Boolean weeklyDigestEnabled) {
        this.weeklyDigestEnabled = weeklyDigestEnabled;
    }

    public Boolean getSubscriptionAlertEnabled() {
        return subscriptionAlertEnabled;
    }

    public void setSubscriptionAlertEnabled(Boolean subscriptionAlertEnabled) {
        this.subscriptionAlertEnabled = subscriptionAlertEnabled;
    }

    public Boolean getSyncFailureAlertEnabled() {
        return syncFailureAlertEnabled;
    }

    public void setSyncFailureAlertEnabled(Boolean syncFailureAlertEnabled) {
        this.syncFailureAlertEnabled = syncFailureAlertEnabled;
    }

    public Integer getBudgetAlertThresholdPercent() {
        return budgetAlertThresholdPercent;
    }

    public void setBudgetAlertThresholdPercent(Integer budgetAlertThresholdPercent) {
        this.budgetAlertThresholdPercent = budgetAlertThresholdPercent;
    }
}
