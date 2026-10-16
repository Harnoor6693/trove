package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code /v1/me}, {@code /v1/me/preferences} and {@code /v1/me/security-events} (§4.2, gap P2-17).
 *
 * <p>The patch semantics are the load-bearing part. The web onboarding flow saves timezone and
 * locale on one screen and {@code onboardingCompleted} on another
 * ({@code apps/web/src/pages/Onboarding/index.tsx:279}), and the route guard refuses to let anyone
 * into the application until that flag is true — so a PUT that read absence as "clear it" would
 * blank the first screen's answers when the second one saved, and the user would be stuck in a
 * loop with no way to tell why.
 */
class PreferencesIT extends AuthFlowSupport {

    private String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    @Test
    @DisplayName("Preferences read back the six authoritative fields, region as an explicit null")
    void preferencesShape() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "prefs-shape");

        HttpResponse<String> response = get(appUrl("/v1/me/preferences"), member.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = Json.data(response.body());
        assertThat(data.propertyNames()).containsExactlyInAnyOrder(
            "timezone", "locale", "currency", "country", "onboardingCompleted", "region");
        assertThat(data.path("region").isNull())
            .as("the client types it string|null and distinguishes 'no province chosen' from "
                + "'not loaded yet'")
            .isTrue();
        assertThat(data.path("timezone").asString()).isEqualTo("America/Toronto");
        assertThat(data.path("locale").asString()).isEqualTo("en-CA");
        assertThat(data.path("onboardingCompleted").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("PUT is a partial patch and returns the whole merged resource")
    void preferencesPatchIndependently() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "prefs-patch");

        HttpResponse<String> first = putJson(appUrl("/v1/me/preferences"),
            Json.object("timezone", "America/Vancouver", "locale", "en-US"),
            "Authorization", bearer(member));
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(Json.data(first.body()).path("onboardingCompleted").asBoolean()).isFalse();

        HttpResponse<String> second = putJson(appUrl("/v1/me/preferences"),
            Json.object("onboardingCompleted", true), "Authorization", bearer(member));

        JsonNode merged = Json.data(second.body());
        assertThat(merged.path("onboardingCompleted").asBoolean()).isTrue();
        assertThat(merged.path("timezone").asString())
            .as("the second screen's save must not blank the first screen's answers")
            .isEqualTo("America/Vancouver");
        assertThat(merged.path("locale").asString()).isEqualTo("en-US");
    }

    @Test
    @DisplayName("region is set, cleared by an explicit null, and untouched when absent")
    void regionIsNullable() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "prefs-region");

        assertThat(Json.data(putJson(appUrl("/v1/me/preferences"),
            Json.object("region", "on"), "Authorization", bearer(member)).body())
            .path("region").asString()).isEqualTo("ON");

        assertThat(Json.data(putJson(appUrl("/v1/me/preferences"),
            Json.object("locale", "en-US"), "Authorization", bearer(member)).body())
            .path("region").asString())
            .as("a patch that never mentions region must not touch it")
            .isEqualTo("ON");

        assertThat(Json.data(putJson(appUrl("/v1/me/preferences"),
            Json.object("region", null), "Authorization", bearer(member)).body())
            .path("region").isNull())
            .as("both clients type region as `string | null` and send Partial<Preferences>, so "
                + "{\"region\": null} is exactly what the 'no province' option produces; on the "
                + "record-shaped DTO it was indistinguishable from absence and cleared nothing")
            .isTrue();

        assertThat(Json.data(putJson(appUrl("/v1/me/preferences"),
            Json.object("region", "bc"), "Authorization", bearer(member)).body())
            .path("region").asString()).isEqualTo("BC");
        assertThat(Json.data(putJson(appUrl("/v1/me/preferences"),
            Json.object("region", ""), "Authorization", bearer(member)).body())
            .path("region").isNull())
            .as("the empty string still clears it, for anything already sending that")
            .isTrue();
    }

    @Test
    @DisplayName("An unsupported locale is a 400, not a 500 from the CHECK constraint")
    void localeIsAClosedSet() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "prefs-locale");

        HttpResponse<String> response = withoutRequestValidation(
            "'fr-CA' is outside PreferencesUpdateRequest.locale's enum on purpose — the enum "
                + "says what the server ACCEPTS, and this test's subject is what it does with a "
                + "value it does not",
            () -> putJson(appUrl("/v1/me/preferences"),
                Json.object("locale", "fr-CA"), "Authorization", bearer(member)));

        assertThat(response.statusCode())
            .as("a value the application accepts and the database refuses is a 500 on save")
            .isEqualTo(400);
        assertThat(Json.parse(response.body()).path("error").path("detail").asString())
            .contains("en-CA");
    }

    @Test
    @DisplayName("An unrecognised timezone is a 400 that names the timezone field")
    void timezoneIsValidated() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "prefs-zone");

        HttpResponse<String> response = putJson(appUrl("/v1/me/preferences"),
            Json.object("timezone", "Mars/Olympus_Mons"), "Authorization", bearer(member));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.parse(response.body()).path("error").path("fieldErrors").path(0)
            .path("field").asString())
            .as("onboarding puts the refusal on the time-zone picker, not in a banner (F1-B6)")
            .isEqualTo("timezone");
    }

    @Test
    @DisplayName("PUT /v1/me returns the full MeResponse the client writes into its store")
    void updateMeReturnsTheWholeIdentity() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "prefs-me");

        HttpResponse<String> response = putJson(appUrl("/v1/me"),
            Json.object("displayName", "Renamed Member", "timezone", "America/Halifax"),
            "Authorization", bearer(member));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode data = Json.data(response.body());
        assertThat(data.path("displayName").asString()).isEqualTo("Renamed Member");
        assertThat(data.path("timezone").asString()).isEqualTo("America/Halifax");
        assertThat(data.path("userId").asString()).isEqualTo(member.userId().toString());
        assertThat(data.has("currency")).isTrue();
    }

    @Test
    @DisplayName("Both endpoints write the same timezone, so the web's double save cannot diverge")
    void timezoneHasOneHome() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "prefs-tz-one");

        putJson(appUrl("/v1/me"), Json.object("timezone", "America/Vancouver"),
            "Authorization", bearer(member));
        assertThat(Json.data(get(appUrl("/v1/me/preferences"), member.accessToken()).body())
            .path("timezone").asString())
            .as("the settings screen writes timezone to BOTH endpoints in one save "
                + "(apps/web/src/pages/Settings/index.tsx:56-60); they address one column")
            .isEqualTo("America/Vancouver");

        putJson(appUrl("/v1/me/preferences"), Json.object("timezone", "America/Toronto"),
            "Authorization", bearer(member));
        assertThat(Json.data(get(appUrl("/v1/auth/me"), member.accessToken()).body())
            .path("timezone").asString()).isEqualTo("America/Toronto");
    }

    @Test
    @DisplayName("Neither endpoint can change currency or country")
    void identityFieldsAreNotWritable() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "prefs-identity");

        putJson(appUrl("/v1/me"), Json.object("displayName", "X", "currency", "USD",
            "country", "US"), "Authorization", bearer(member));
        putJson(appUrl("/v1/me/preferences"), Json.object("currency", "USD", "country", "US"),
            "Authorization", bearer(member));

        JsonNode me = Json.data(get(appUrl("/v1/auth/me"), member.accessToken()).body());
        assertThat(me.path("currency").asString())
            .as("every historical transaction, budget and export assumes a stable reporting "
                + "currency; unknown properties are ignored rather than 400ing the request")
            .isEqualTo("CAD");
        assertThat(me.path("country").asString()).isEqualTo("CA");
    }

    @Test
    @DisplayName("A member sees their own security events and nobody else's")
    void securityEventsAreSelfScoped() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "prefs-events");
        loginBearer(member.email(), MEMBER_PASSWORD);

        HttpResponse<String> response =
            get(appUrl("/v1/me/security-events"), member.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        var events = Json.elements(Json.data(response.body()));
        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.propertyNames())
                .containsExactlyInAnyOrder("id", "type", "at", "ip", "userAgent");
            assertThat(event.path("type").asString()).isNotBlank();
        });
        assertThat(events).anySatisfy(event ->
            assertThat(event.path("type").asString()).isEqualTo("LOGIN_OK"));

        var adminEvents = Json.elements(Json.data(
            get(appUrl("/v1/me/security-events"), admin.accessToken()).body()));
        assertThat(adminEvents.stream().map(e -> e.path("id").asString()).toList())
            .as("a security log is a list of where somebody has signed in from; handing one "
                + "household member another's would be worse than most of the ledger")
            .doesNotContainAnyElementsOf(events.stream().map(e -> e.path("id").asString())
                .toList());
    }

    @Test
    @DisplayName("Every /v1/me route is 401 anonymously")
    void meRoutesRequireASession() {
        assertThat(get(appUrl("/v1/me/preferences")).statusCode()).isEqualTo(401);
        assertThat(get(appUrl("/v1/me/security-events")).statusCode()).isEqualTo(401);
        assertThat(putJson(appUrl("/v1/me"), Json.object("displayName", "X")).statusCode())
            .isEqualTo(401);
    }
}
