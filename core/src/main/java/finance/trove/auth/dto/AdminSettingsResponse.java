package finance.trove.auth.dto;

/**
 * {@code GET/PUT /v1/admin/settings}. The live value lives on {@code households.registration_open}
 * (V3), not on {@code trove.auth.registration-open}: an admin's runtime decision must survive a
 * restart rather than reverting to whatever the environment file still says.
 */
public record AdminSettingsResponse(boolean registrationOpen) {
}
