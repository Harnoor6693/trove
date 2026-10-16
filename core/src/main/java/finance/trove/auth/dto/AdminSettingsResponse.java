package finance.trove.auth.dto;

import finance.trove.households.Household;

/**
 * {@code GET/PUT /v1/admin/settings}. The live values live on the household row
 * ({@code registration_open}, V3; {@code scheduled_sync_enabled}, V45), not on
 * {@code trove.auth.registration-open}: an admin's runtime decision must survive a restart rather
 * than reverting to whatever the environment file still says.
 */
public record AdminSettingsResponse(boolean registrationOpen, boolean scheduledSyncEnabled) {

    public static AdminSettingsResponse of(Household household) {
        return new AdminSettingsResponse(household.isRegistrationOpen(),
            household.isScheduledSyncEnabled());
    }
}
