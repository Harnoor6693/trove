package finance.trove.users;

import finance.trove.auth.dto.PreferencesUpdateRequest;
import finance.trove.auth.dto.UpdateMeRequest;
import finance.trove.common.exception.UnauthorizedException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.time.UserTimeZoneProvider;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the profile fields behind {@code /v1/auth/me}, {@code /v1/me} and
 * {@code /v1/me/preferences} (§4.2).
 *
 * <p><strong>These fields live on {@code users}, not on a {@code user_preferences} table.</strong>
 * The plan's V10 is redundant as a result and is not created. The chain that got here: the plan's
 * own database review moved {@code currency} and {@code country} onto {@code users} because five
 * other packages read them directly; {@code timezone} followed, because {@code UserClock} resolves
 * a zone for every per-user date boundary and a second table on that path is a second query on
 * every dashboard read; {@code locale} and {@code onboarding_completed} followed those, because
 * the web route guard reads {@code onboardingCompleted} on every navigation
 * ({@code apps/web/src/router/AuthGuard.tsx:81}). What was left for V10 to hold was
 * {@code region} alone. The endpoint keeps its contract shape either way, which is the part the
 * clients can see.
 *
 * <p><strong>Both writing endpoints are patches.</strong> Absent means "leave alone". The web
 * onboarding flow saves timezone and locale on one screen and {@code onboardingCompleted} on
 * another ({@code apps/web/src/pages/Onboarding/index.tsx:279}); a PUT that read absence as
 * "clear it" would blank the first screen's answers when the second saved. {@code region} is the
 * one field that can be cleared, by sending an empty string, which is what the "no province"
 * option submits.
 *
 * <p>{@code timezone} is writable through <em>both</em> {@code PUT /v1/me} and
 * {@code PUT /v1/me/preferences}, because the web settings screen writes it to both in one save
 * ({@code apps/web/src/pages/Settings/index.tsx:56-60}). They address the same column, so the two
 * writes cannot disagree — which is the resolution to the open question that file leaves in a
 * comment.
 */
@Service
public class UserProfileService {

    /** The closed set of {@code users.locale}; V3's CHECK constraint enforces the same two. */
    private static final Set<String> LOCALES = Set.of("en-CA", "en-US");

    private final UserRepository users;
    private final UserTimeZoneProvider zones;

    public UserProfileService(UserRepository users, UserTimeZoneProvider zones) {
        this.users = users;
        this.zones = zones;
    }

    /**
     * The caller's own row.
     *
     * <p>Loaded with {@code findByIdAndHouseholdId} even though the id alone would find it. Both
     * halves come from the signed token, so the extra predicate costs nothing and means this call
     * site is not an example a later step can copy into a place where the id <em>is</em>
     * attacker-supplied (§9 risk 2).
     */
    @Transactional(readOnly = true)
    public User require(UUID userId, UUID householdId) {
        return users.findByIdAndHouseholdId(userId, householdId)
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired token"));
    }

    /** {@code PUT /v1/me} — display name and timezone; nothing identity-fixed. */
    @Transactional
    public User updateProfile(UUID userId, UUID householdId, UpdateMeRequest request) {
        User user = require(userId, householdId);
        if (request.displayName() != null) {
            String name = request.displayName().strip();
            if (name.isEmpty()) {
                throw new ValidationException("Display name cannot be blank.");
            }
            user.setDisplayName(name);
        }
        if (request.timezone() != null) {
            user.setTimezone(validZone(request.timezone()));
            zones.evict(userId);
        }
        return users.save(user);
    }

    /** {@code PUT /v1/me/preferences} — the partial patch described above. */
    @Transactional
    public User updatePreferences(UUID userId, UUID householdId,
                                  PreferencesUpdateRequest request) {
        User user = require(userId, householdId);
        if (request.timezone() != null) {
            user.setTimezone(validZone(request.timezone()));
            zones.evict(userId);
        }
        if (request.locale() != null) {
            user.setLocale(validLocale(request.locale()));
        }
        if (request.onboardingCompleted() != null) {
            user.setOnboardingCompleted(request.onboardingCompleted());
        }
        if (request.regionPresent()) {
            // An explicit null clears it, an absent key leaves it alone, and the empty string
            // still clears it. Both clients type region as `string | null` and send patches as
            // Partial<Preferences>, so `{"region": null}` is literally what the "no province"
            // option produces — on the old record-shaped DTO that was indistinguishable from
            // absence and the field could not be cleared at all.
            String region = request.region() == null
                ? null
                : request.region().strip().toUpperCase(Locale.ROOT);
            user.setRegion(region == null || region.isEmpty() ? null : region);
        }
        return users.save(user);
    }

    /**
     * Validates an IANA zone id.
     *
     * <p>Unlike registration, which falls back to UTC rather than lose a signup, an explicit
     * settings change is refused when the zone is unknown: the user chose it from a list and is
     * looking at the result, so silently storing something else would be the confusing outcome.
     */
    private static String validZone(String candidate) {
        try {
            return ZoneId.of(candidate.strip()).getId();
        } catch (DateTimeException e) {
            throw new ValidationException(
                "'" + candidate + "' is not a recognised IANA time zone.");
        }
    }

    private static String validLocale(String candidate) {
        String value = candidate.strip();
        if (!LOCALES.contains(value)) {
            throw new ValidationException(
                "Locale must be one of " + String.join(", ", LOCALES) + ".");
        }
        return value;
    }
}
