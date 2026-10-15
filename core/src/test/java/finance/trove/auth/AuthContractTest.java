package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.auth.dto.AuthUser;
import finance.trove.auth.dto.InvitePreviewResponse;
import finance.trove.auth.dto.InviteResponse;
import finance.trove.auth.dto.LoginResponse;
import finance.trove.auth.dto.MeResponse;
import finance.trove.auth.dto.PreferencesResponse;
import finance.trove.auth.dto.ProvidersResponse;
import finance.trove.auth.dto.RefreshResponse;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.dto.ApiResponse;
import finance.trove.users.User;
import finance.trove.users.UserRole;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The wire shapes both clients were written against, asserted field by field.
 *
 * <p>These are contract tests, not serialisation tests. Every expectation below is traceable to a
 * line in a shipped client: the web SPA and the Expo app were both written before this server
 * existed, from the login contract written down before the server, and a field renamed or omitted
 * here is a screen that breaks with no compiler to catch it.
 *
 * <p>The mapper is a plain one rather than the application's configured bean, so that a setting
 * which is wrong in both directions cannot cancel itself out.
 */
class AuthContractTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static User user() {
        User user = new User(UUID.randomUUID(), UserRole.ADMIN, "member@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("America/Toronto"));
        try {
            var field = User.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(user, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return user;
    }

    @Test
    @DisplayName("Cookie-mode login returns {user} and NEVER a null data (handoff point 5)")
    void cookieLoginCarriesTheUser() {
        String json = MAPPER.writeValueAsString(
            ApiResponse.ok(LoginResponse.cookie(AuthUser.of(user(), false))));

        var data = MAPPER.readTree(json).path("data");
        assertThat(data.isNull())
            .as("the SPA's unwrap() throws on a null data, so a 'no content needed' body would "
                + "fail every login in the browser (apps/web/src/api/client.ts:292)")
            .isFalse();
        assertThat(data.has("user")).isTrue();
        assertThat(data.has("accessToken"))
            .as("the whole point of an HttpOnly cookie is that a script cannot read the session; "
                + "echoing it into a readable field hands it straight back")
            .isFalse();
        assertThat(data.has("refreshToken")).isFalse();
        assertThat(data.has("challengeToken"))
            .as("clients discriminate on the PRESENCE of challengeToken")
            .isFalse();
    }

    @Test
    @DisplayName("Bearer-mode login returns user plus both tokens and expiresIn in seconds")
    void bearerLoginCarriesTokens() {
        var data = MAPPER.readTree(MAPPER.writeValueAsString(ApiResponse.ok(
            LoginResponse.bearer(AuthUser.of(user(), true), "access", "refresh", 900))))
            .path("data");

        assertThat(data.path("accessToken").asString()).isEqualTo("access");
        assertThat(data.path("refreshToken").asString()).isEqualTo("refresh");
        assertThat(data.path("expiresIn").asInt()).isEqualTo(900);
        assertThat(data.path("user").path("totpEnabled").asBoolean()).isTrue();
        assertThat(data.has("challengeToken")).isFalse();
    }

    @Test
    @DisplayName("A TOTP challenge carries challengeToken and nothing else")
    void totpChallengeIsBare() {
        var data = MAPPER.readTree(MAPPER.writeValueAsString(
            ApiResponse.ok(LoginResponse.challenge("the-challenge")))).path("data");

        assertThat(data.path("challengeToken").asString()).isEqualTo("the-challenge");
        assertThat(data.has("user")).isFalse();
        assertThat(data.has("accessToken")).isFalse();
    }

    @Test
    @DisplayName("AuthUser carries id (not userId) and householdId, as both clients declare")
    void authUserFieldNames() {
        var json = MAPPER.readTree(MAPPER.writeValueAsString(AuthUser.of(user(), false)));

        assertThat(json.has("id")).isTrue();
        assertThat(json.has("userId"))
            .as("apps/web/src/api/types.ts:98 declares AuthUser.id; MeResponse is the one that "
                + "uses userId")
            .isFalse();
        assertThat(json.has("email")).isTrue();
        assertThat(json.has("displayName")).isTrue();
        assertThat(json.has("householdId"))
            .as("web requires it on the login body too (apps/web/src/api/types.ts:101)")
            .isTrue();
        assertThat(json.has("totpEnabled")).isTrue();
    }

    @Test
    @DisplayName("GET /v1/auth/me carries all eleven fields the two clients read between them")
    void meResponseFieldNames() {
        var json = MAPPER.readTree(MAPPER.writeValueAsString(MeResponse.of(user(), true)));

        assertThat(json.has("userId"))
            .as("camelCase; the gateway was the one place snake_case actually ran (§5.4)")
            .isTrue();
        assertThat(json.has("user_id")).isFalse();
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
            "userId", "email", "displayName", "householdId", "role", "totpEnabled",
            "currency", "country", "timezone", "locale", "onboardingCompleted");
        assertThat(json.path("role").asString()).isEqualTo("ADMIN");
        assertThat(json.path("currency").asString()).isEqualTo("CAD");
        assertThat(json.path("country").asString()).isEqualTo("CA");
        assertThat(json.path("locale").asString()).isEqualTo("en-CA");
    }

    @Test
    @DisplayName("Preferences emits region as an explicit null, not an absent key")
    void preferencesEmitsNullRegion() {
        var json = MAPPER.readTree(MAPPER.writeValueAsString(PreferencesResponse.of(user())));

        assertThat(json.has("region"))
            .as("web types it string|null and distinguishes 'no province chosen' from 'not "
                + "loaded yet' (apps/web/src/api/types.ts:177)")
            .isTrue();
        assertThat(json.path("region").isNull()).isTrue();
        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
            "timezone", "locale", "currency", "country", "onboardingCompleted", "region");
    }

    @Test
    @DisplayName("Providers emits oidc.displayName as null rather than omitting it")
    void providersEmitsNullsExplicitly() {
        var off = MAPPER.readTree(MAPPER.writeValueAsString(
            new ProvidersResponse(false, true, ProvidersResponse.Oidc.disabled())));

        assertThat(off.path("registrationOpen").asBoolean()).isFalse();
        assertThat(off.path("local").asBoolean()).isTrue();
        assertThat(off.path("oidc").path("enabled").asBoolean()).isFalse();
        assertThat(off.path("oidc").has("displayName"))
            .as("web renders {oidc.displayName ?? '...'} and types it string|null")
            .isTrue();
        assertThat(off.path("oidc").path("displayName").isNull()).isTrue();

        var on = MAPPER.readTree(MAPPER.writeValueAsString(new ProvidersResponse(true, true,
            ProvidersResponse.Oidc.enabled(
                new AuthProperties(false, "https://trove.example").oidcStartUrl(),
                "Company SSO"))));
        assertThat(on.path("oidc").path("startUrl").asString())
            .as("mobile types this 'the absolute URL the client opens' and hands it to a system "
                + "browser, which has no origin to resolve a relative path against")
            .isEqualTo("https://trove.example/api/v1/auth/start");
        assertThat(on.path("oidc").path("displayName").asString()).isEqualTo("Company SSO");
    }

    @Test
    @DisplayName("An invite emits every field, nulls included")
    void inviteEmitsNullsExplicitly() {
        var json = MAPPER.readTree(MAPPER.writeValueAsString(new InviteResponse(
            UUID.randomUUID(), null, "tok", "https://trove.test/invite/tok",
            Instant.parse("2026-09-07T12:00:00Z"), Instant.parse("2026-09-04T12:00:00Z"),
            null, null)));

        assertThat(json.propertyNames()).containsExactlyInAnyOrder(
            "id", "email", "token", "url", "expiresAt", "createdAt", "acceptedAt",
            "createdByEmail");
        assertThat(json.path("email").isNull()).isTrue();
        assertThat(json.path("acceptedAt").isNull()).isTrue();
        assertThat(json.path("expiresAt").asString())
            .as("ISO-8601, not epoch millis; write-dates-as-timestamps is off (§5.4)")
            .isEqualTo("2026-09-07T12:00:00Z");
    }

    @Test
    @DisplayName("An invite preview is always three fields, whatever the verdict")
    void invitePreviewShape() {
        var invalid = MAPPER.readTree(MAPPER.writeValueAsString(
            InvitePreviewResponse.invalid()));

        assertThat(invalid.propertyNames()).containsExactlyInAnyOrder("valid", "email",
            "expiresAt");
        assertThat(invalid.path("valid").asBoolean()).isFalse();
        assertThat(invalid.path("email").isNull())
            .as("an invalid token must not disclose whom it was for")
            .isTrue();
    }

    @Test
    @DisplayName("A cookie-mode refresh returns the lifetime only, never the rotated tokens")
    void refreshBodyRespectsTheMode() {
        var cookie = MAPPER.readTree(MAPPER.writeValueAsString(RefreshResponse.cookie(900)));
        assertThat(cookie.has("accessToken")).isFalse();
        assertThat(cookie.has("refreshToken")).isFalse();
        assertThat(cookie.path("expiresIn").asInt()).isEqualTo(900);

        var bearer = MAPPER.readTree(
            MAPPER.writeValueAsString(RefreshResponse.bearer("a", "r", 900)));
        assertThat(bearer.path("accessToken").asString()).isEqualTo("a");
        assertThat(bearer.path("refreshToken").asString()).isEqualTo("r");
    }

    @Test
    @DisplayName("The envelope is {success, data} and omits absent error and meta")
    void envelopeShape() {
        var json = MAPPER.readTree(MAPPER.writeValueAsString(ApiResponse.ok("x")));
        assertThat(json.propertyNames()).containsExactlyInAnyOrder("success", "data");
        assertThat(json.path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("The auth-mode header defaults to bearer, which is the safe direction")
    void authModeParsing() {
        assertThat(AuthMode.from("cookie")).isEqualTo(AuthMode.COOKIE);
        assertThat(AuthMode.from("COOKIE")).isEqualTo(AuthMode.COOKIE);
        assertThat(AuthMode.from("  cookie ")).isEqualTo(AuthMode.COOKIE);
        assertThat(AuthMode.from("bearer")).isEqualTo(AuthMode.BEARER);
        assertThat(AuthMode.from(null))
            .as("a caller that forgets the header gets tokens in a body it can ignore; "
                + "defaulting to cookies would set session cookies for clients that never asked")
            .isEqualTo(AuthMode.BEARER);
        assertThat(AuthMode.from("nonsense")).isEqualTo(AuthMode.BEARER);
        assertThat(AuthMode.HEADER).isEqualTo("X-Trove-Auth-Mode");
    }

    @Test
    @DisplayName("Generated links have exactly one slash, whatever the configured origin")
    void publicUrlNormalisation() {
        for (String configured : new String[] {
            "https://trove.example.com", "https://trove.example.com/",
            "https://trove.example.com///", "  https://trove.example.com/  "}) {
            AuthProperties properties = new AuthProperties(false, configured);
            assertThat(properties.inviteUrl("tok"))
                .isEqualTo("https://trove.example.com/invite/tok");
            assertThat(properties.passwordResetUrl("tok"))
                .isEqualTo("https://trove.example.com/reset-password/tok");
        }
        assertThat(new AuthProperties(false, null).publicUrl())
            .isEqualTo("http://localhost:5173");
    }
}
