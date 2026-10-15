package finance.trove.auth.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/auth/register} (handoff §4a point 6).
 *
 * <p>{@code inviteToken} is optional in the type and mandatory in practice: registration is closed
 * by default (gap P2-7), so without a token the service answers {@code registration-closed}. It is
 * nullable rather than {@code @NotBlank} so that an install which has deliberately opened
 * self-serve signup can accept the same body with the field absent.
 *
 * <p>{@code currency}, {@code country} and {@code timezone} are optional and default to CAD / CA /
 * UTC. The web onboarding flow always sends them; the invite-acceptance form does not always have
 * them yet, and refusing a registration because a browser could not report its timezone would be a
 * poor trade. They are identity-level and set once — {@code PUT /v1/me} deliberately cannot change
 * currency or country afterwards, because every historical row assumes a stable reporting currency.
 */
public record RegisterRequest(
    @Size(max = 128) String inviteToken,
    @NotBlank @Email @Size(max = 320) String email,
    @NotBlank @Size(max = 1024) String password,
    @NotBlank @Size(max = 100) String displayName,
    CurrencyCode currency,
    Jurisdiction country,
    @Size(max = 64) String timezone) {
}
