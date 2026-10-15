package finance.trove.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/auth/password/reset} — public, and the token is the whole authorisation.
 *
 * <p>Redeeming it consumes the token, sets the password, and revokes every refresh token the
 * account has (§1.5): a reset is what someone does when they believe their password is known to
 * somebody else, so leaving that somebody else's sessions alive would defeat the exercise.
 */
public record PasswordResetRequest(
    @NotBlank @Size(max = 128) String token,
    @NotBlank @Size(max = 1024) String password) {
}
