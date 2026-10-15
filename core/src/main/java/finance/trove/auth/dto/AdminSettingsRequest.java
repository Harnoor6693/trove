package finance.trove.auth.dto;

/**
 * {@code PUT /v1/admin/settings} — {@code {registrationOpen}} (§4.2).
 *
 * <p>Nullable so the body is a patch: today there is exactly one setting, and a required field
 * would have to be re-sent unchanged by every future caller that only wants to change the next
 * one.
 */
public record AdminSettingsRequest(Boolean registrationOpen) {
}
