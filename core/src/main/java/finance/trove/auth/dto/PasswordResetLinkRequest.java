package finance.trove.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/auth/password/reset-request} — ADMIN only (gap P2-8).
 *
 * <p>Most self-hosters have no SMTP server, so "forgot password" cannot be an email. It is an
 * admin generating a one-hour single-use link and handing it over out of band. That is why this
 * route is authenticated and admin-scoped rather than public: a public route that mints reset
 * links would be a password-reset oracle with no second channel to gate it.
 */
public record PasswordResetLinkRequest(@NotBlank @Email @Size(max = 320) String email) {
}
