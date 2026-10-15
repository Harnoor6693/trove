package finance.trove.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code DELETE /v1/auth/totp} — sent as a DELETE with a JSON body, which is how the mobile
 * client already calls it ({@code apps/mobile/src/api/auth.ts:100}:
 * {@code api.delete('/v1/auth/totp', { data: { password, code } })}).
 *
 * <p>Both halves are required: this is the step-up of §1.5. Removing the second factor with only
 * a session would make the second factor removable by precisely the attacker it exists to stop.
 */
public record TotpDisableRequest(
    @NotBlank @Size(max = 1024) String password,
    @NotBlank @Size(max = 32) String code) {
}
