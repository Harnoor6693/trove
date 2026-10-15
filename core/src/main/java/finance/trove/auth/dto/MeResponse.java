package finance.trove.auth.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.users.User;
import finance.trove.users.UserRole;
import java.util.UUID;

/**
 * {@code GET /v1/auth/me} — the authoritative session read (handoff §4a point 1).
 *
 * <p>Every field is load-bearing on a client that already exists:
 * <ul>
 *   <li>{@code userId} (not {@code user_id} — the gateway was the one place snake_case actually
 *       ran, §5.4) and {@code displayName}, which the pre-collapse body did not carry at all;</li>
 *   <li>{@code householdId} and {@code role}, so the SPA can hide admin-only navigation without
 *       probing an admin route and reading the 403;</li>
 *   <li>{@code currency}, {@code country}, {@code timezone} and {@code locale}, which are what
 *       every amount and every date on both clients is formatted with. They live on {@code users}
 *       (§3 V3), not behind a second preferences call, because the alternative is a first paint
 *       with the wrong currency symbol;</li>
 *   <li>{@code onboardingCompleted}, which the route guard reads on every navigation;</li>
 *   <li>{@code totpEnabled}, which the settings screen renders the second-factor section from.</li>
 * </ul>
 */
public record MeResponse(
    UUID userId,
    String email,
    String displayName,
    UUID householdId,
    UserRole role,
    boolean totpEnabled,
    CurrencyCode currency,
    Jurisdiction country,
    String timezone,
    String locale,
    boolean onboardingCompleted) {

    public static MeResponse of(User user, boolean totpEnabled) {
        return new MeResponse(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.getHouseholdId(),
            user.getRole(),
            totpEnabled,
            user.getCurrency(),
            user.getCountry(),
            user.getTimezone(),
            user.getLocale(),
            user.isOnboardingCompleted());
    }
}
