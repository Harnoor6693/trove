package finance.trove.notifications.preference.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import finance.trove.notifications.preference.NotificationPreference;
import java.time.Instant;

/**
 * {@code GET /v1/notifications/preferences} and the echo of {@code PUT} (§4.9).
 *
 * <p><strong>There is no push field here, in any form.</strong> Not {@code pushEnabled}, not a
 * device token, not a device list. The spec's response schema is closed, so a future field that
 * accidentally reintroduced one would fail the conformance check on every integration test rather
 * than ship — which is the same mechanism that keeps the AI API key out of
 * {@code AiSettingsResponse}. {@code taxReminderEnabled} is gone with the tax engine.
 *
 * <p>{@code @JsonInclude(ALWAYS)}, so every field is on the wire even when null and the generated
 * client types can mark the nullable one {@code | null} rather than optional. The spec's
 * {@code required} list matches this exactly.
 *
 * @param inAppEnabled               whether a new notification announces itself; the inbox row is
 *                                   written either way
 * @param emailEnabled               whether the email channel is attempted; false by default
 * @param emailAddress               where email goes, or null
 * @param weeklyDigestEnabled        whether this member is in the Monday digest
 * @param subscriptionAlertEnabled   whether a detected price change notifies them
 * @param syncFailureAlertEnabled    whether a failed sync notifies them
 * @param budgetAlertThresholdPercent whole percent of an envelope's allocation, 1-200
 * @param updatedAt                  when the row was last written
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NotificationPreferencesResponse(
    boolean inAppEnabled,
    boolean emailEnabled,
    String emailAddress,
    boolean weeklyDigestEnabled,
    boolean subscriptionAlertEnabled,
    boolean syncFailureAlertEnabled,
    int budgetAlertThresholdPercent,
    Instant updatedAt) {

    public static NotificationPreferencesResponse of(NotificationPreference preference) {
        return new NotificationPreferencesResponse(
            preference.isInAppEnabled(),
            preference.isEmailEnabled(),
            preference.getEmailAddress(),
            preference.isWeeklyDigestEnabled(),
            preference.isSubscriptionAlertEnabled(),
            preference.isSyncFailureAlertEnabled(),
            preference.getBudgetAlertThresholdPercent(),
            preference.getUpdatedAt());
    }
}
