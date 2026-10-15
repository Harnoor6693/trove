package finance.trove.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.auth.dto.PreferencesUpdateRequest;
import finance.trove.auth.dto.UpdateMeRequest;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.exception.UnauthorizedException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.time.UserTimeZoneProvider;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Patch semantics for {@code PUT /v1/me} and {@code PUT /v1/me/preferences} (§4.2).
 *
 * <p>Absent means "leave alone" throughout. The web onboarding flow saves timezone and locale on
 * one screen and {@code onboardingCompleted} on another, so a PUT that read absence as "clear it"
 * would blank the first screen's answers when the second one saved — and the second screen is the
 * one that lets the user into the application at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserProfileServiceTest {

    @Mock
    private UserRepository users;

    @Mock
    private UserTimeZoneProvider zones;

    private UserProfileService service;
    private User user;
    private final UUID householdId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserProfileService(users, zones);
        user = new User(householdId, UserRole.MEMBER, "member@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("America/Toronto"));
        when(users.findByIdAndHouseholdId(any(), any())).thenReturn(Optional.of(user));
        when(users.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("A user outside the caller's household is not found, never returned")
    void lookupIsHouseholdScoped() {
        when(users.findByIdAndHouseholdId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.require(UUID.randomUUID(), householdId))
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("PUT /v1/me changes only what it names")
    void updateProfileIsAPatch() {
        service.updateProfile(user.getId(), householdId, new UpdateMeRequest("Renamed", null));

        assertThat(user.getDisplayName()).isEqualTo("Renamed");
        assertThat(user.getTimezone())
            .as("an absent field must not be read as 'clear it'")
            .isEqualTo("America/Toronto");
    }

    @Test
    @DisplayName("A blank display name is refused rather than silently stored")
    void blankDisplayNameIsRefused() {
        assertThatThrownBy(() ->
            service.updateProfile(user.getId(), householdId, new UpdateMeRequest("   ", null)))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("Changing the timezone evicts the cached zone, or 'today' stays stale")
    void timezoneChangeEvictsTheCache() {
        service.updateProfile(user.getId(), householdId,
            new UpdateMeRequest(null, "America/Vancouver"));

        assertThat(user.getTimezone()).isEqualTo("America/Vancouver");
        verify(zones)
            .evict(user.getId());
    }

    @Test
    @DisplayName("An unrecognised timezone on an explicit settings change is refused")
    void invalidTimezoneIsRefused() {
        assertThatThrownBy(() -> service.updateProfile(user.getId(), householdId,
            new UpdateMeRequest(null, "Mars/Olympus_Mons")))
            .as("unlike registration, which falls back to UTC rather than lose a signup, the "
                + "user is looking at this result and picked it from a list")
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("IANA");
    }

    @Test
    @DisplayName("Preferences patch each field independently")
    void preferencesPatchIndependently() {
        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withLocale("en-US"));
        assertThat(user.getLocale()).isEqualTo("en-US");
        assertThat(user.isOnboardingCompleted()).isFalse();

        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withOnboardingCompleted(true));
        assertThat(user.isOnboardingCompleted()).isTrue();
        assertThat(user.getLocale())
            .as("the second screen's save must not blank the first screen's answers")
            .isEqualTo("en-US");
    }

    @Test
    @DisplayName("Only en-CA and en-US are accepted — the same two the CHECK constraint allows")
    void localeIsAClosedSet() {
        assertThatThrownBy(() -> service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withLocale("fr-CA")))
            .as("a value the application accepts and the database refuses is a 500 on save")
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("en-CA");
    }

    @Test
    @DisplayName("region is set upper-cased, and cleared by an empty string")
    void regionIsNullable() {
        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withRegion("on"));
        assertThat(user.getRegion()).isEqualTo("ON");

        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withRegion(""));
        assertThat(user.getRegion())
            .as("the 'no province' option submits an empty string; absence means leave alone")
            .isNull();
    }

    @Test
    @DisplayName("An explicit null clears region; an absent key leaves it alone")
    void anExplicitNullClearsRegion() {
        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withRegion("ON"));
        assertThat(user.getRegion()).isEqualTo("ON");

        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withLocale("en-US"));
        assertThat(user.getRegion())
            .as("a patch that never mentions region must not touch it")
            .isEqualTo("ON");

        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withRegion(null));
        assertThat(user.getRegion())
            .as("both clients type region as `string | null` and send Partial<Preferences>, so "
                + "{\"region\": null} is what the 'no province' option produces")
            .isNull();
    }

    @Test
    @DisplayName("An entirely empty patch is a no-op, not a wipe")
    void emptyPatchChangesNothing() {
        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch());

        assertThat(user.getTimezone()).isEqualTo("America/Toronto");
        assertThat(user.getLocale()).isEqualTo("en-CA");
        assertThat(user.getRegion()).isNull();
        assertThat(user.isOnboardingCompleted()).isFalse();
    }

    @Test
    @DisplayName("Neither endpoint can change currency or country")
    void identityFieldsAreNotWritable() {
        service.updateProfile(user.getId(), householdId, new UpdateMeRequest("X", "UTC"));
        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withTimezone("UTC").withLocale("en-US")
                .withOnboardingCompleted(true).withRegion("BC"));

        assertThat(user.getCurrency())
            .as("every historical transaction, budget and export assumes a stable reporting "
                + "currency (§4.2)")
            .isEqualTo(CurrencyCode.CAD);
        assertThat(user.getCountry()).isEqualTo(Jurisdiction.CA);
    }

    @Test
    @DisplayName("Both endpoints write the same timezone column, so they cannot disagree")
    void timezoneHasOneHome() {
        service.updateProfile(user.getId(), householdId, new UpdateMeRequest(null, "UTC"));
        assertThat(user.getTimezone()).isEqualTo("UTC");

        service.updatePreferences(user.getId(), householdId,
            PreferencesUpdateRequest.patch().withTimezone("America/Halifax"));
        assertThat(user.getTimezone())
            .as("the web settings screen writes timezone to both endpoints in one save "
                + "(apps/web/src/pages/Settings/index.tsx:56-60)")
            .isEqualTo("America/Halifax");
    }
}
