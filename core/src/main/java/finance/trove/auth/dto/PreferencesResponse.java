package finance.trove.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.users.User;

/**
 * {@code GET /v1/me/preferences} and the echo of {@code PUT} (handoff §4a point 2).
 *
 * <p>This endpoint is authoritative for the six fields below. They are stored on {@code users}
 * (§3 V3) rather than in a {@code user_preferences} table: {@code currency}/{@code country} were
 * moved there by the plan's own database review because five other packages read them directly,
 * and once {@code locale}, {@code timezone} and {@code onboarding_completed} joined them the
 * second table held nothing but {@code region}. Keeping the endpoint's shape and dropping the
 * table costs a client nothing and saves a join on the request every page load makes.
 *
 * <p>{@code region} is nullable by contract — {@code string | null}, not an absent key — because
 * the client distinguishes "no province chosen" from "not loaded yet". It is therefore the one
 * field here exempt from the NON_NULL include.
 *
 * @param timezone            IANA zone, the basis of every "today" boundary (gap P2-15)
 * @param locale              {@code en-CA} or {@code en-US}; the only two the CHECK allows
 * @param currency            reporting currency, read-only through this endpoint
 * @param country             CA or US, read-only through this endpoint
 * @param onboardingCompleted whether the setup flow has been finished
 * @param region              optional province/state code, or null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PreferencesResponse(
    String timezone,
    String locale,
    CurrencyCode currency,
    Jurisdiction country,
    boolean onboardingCompleted,
    String region) {

    public static PreferencesResponse of(User user) {
        return new PreferencesResponse(user.getTimezone(), user.getLocale(), user.getCurrency(),
            user.getCountry(), user.isOnboardingCompleted(), user.getRegion());
    }
}
