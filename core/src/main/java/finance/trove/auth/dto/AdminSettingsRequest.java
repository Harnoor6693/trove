package finance.trove.auth.dto;

/**
 * {@code PUT /v1/admin/settings} — {@code {registrationOpen, scheduledSyncEnabled}} (§4.2, C-01r).
 *
 * <p>Nullable so the body is a patch: a required field would have to be re-sent unchanged by every
 * caller that only wants to change the other one.
 */
public record AdminSettingsRequest(Boolean registrationOpen, Boolean scheduledSyncEnabled) {
}
