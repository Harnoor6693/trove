package finance.trove.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/auth/password} — field names taken verbatim from the mobile client, which is
 * the only one that implements this today ({@code apps/mobile/src/api/types.ts:183-186}).
 *
 * <p>{@code currentPassword} is the re-authentication: knowing a session is not the same as
 * knowing the password, and a borrowed unlocked laptop is the exact case this defends against.
 * A successful change revokes every other session for the account (§1.5).
 */
public record PasswordChangeRequest(
    @NotBlank @Size(max = 1024) String currentPassword,
    @NotBlank @Size(max = 1024) String newPassword) {
}
