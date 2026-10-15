package finance.trove.auth.dto;

import finance.trove.users.User;
import finance.trove.users.UserRole;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /v1/admin/users} (§4.2).
 *
 * <p>What a household admin needs in order to run the household: who is in it, which of them can
 * administer it, who still has a working second factor, and who to unlock. Nothing else — this is
 * an administration list, not a profile dump, so preferences, locale, timezone and region stay on
 * {@code GET /v1/auth/me}, which is the owner's own read of their own row.
 *
 * @param totpEnabled so the "clear this member's second factor" action can be shown only where it
 *                    would do something ({@code DELETE /v1/admin/users/{id}/totp})
 * @param active      the soft-delete flag; a deactivated member still occupies a row and still
 *                    owns ledger history, so an admin has to be able to see the difference
 */
public record AdminUserResponse(
    UUID id,
    String email,
    String displayName,
    UserRole role,
    boolean active,
    boolean totpEnabled,
    Instant createdAt) {

    public static AdminUserResponse of(User user, boolean totpEnabled) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
            user.getRole(), user.isActive(), totpEnabled, user.getCreatedAt());
    }
}
