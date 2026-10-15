package finance.trove.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/auth/totp/setup} body — {@code {password}}, as the mobile client sends it
 * ({@code apps/mobile/src/api/auth.ts:82}).
 *
 * <p>Enrolment requires a fresh password even though the caller already holds a session (§1.5,
 * gap P2-6). Adding a second factor to an account is a change to how that account is secured, and
 * a session token alone is exactly the credential an attacker who borrowed a logged-in device has.
 */
public record TotpSetupRequest(@NotBlank @Size(max = 1024) String password) {
}
