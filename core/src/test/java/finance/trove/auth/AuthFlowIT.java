package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.auth.jwt.AuthCookies;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The end-to-end path the handoff contract describes: invite, register, sign in both ways, read
 * the session, rotate it, sign out.
 *
 * <p>Both delivery modes are exercised against the same endpoints, because the whole point of
 * {@code X-Trove-Auth-Mode} is that one server serves a browser and a phone whose requirements are
 * opposite — and a mode that is only ever tested one way is a mode that breaks the other client on
 * the day it ships.
 */
class AuthFlowIT extends AuthFlowSupport {

    @Test
    @DisplayName("Invite, register, sign in, read the session, rotate, sign out — bearer mode")
    void bearerModeRoundTrip() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("bearer");
        Session member = registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);

        assertThat(member.householdId())
            .as("an invite pre-binds the household, so a member lands in the admin's household "
                + "and never in one a request body named")
            .isEqualTo(admin.householdId());

        HttpResponse<String> me = get(appUrl("/v1/auth/me"), member.accessToken());
        assertThat(me.statusCode()).isEqualTo(200);
        JsonNode data = Json.data(me.body());
        assertThat(data.path("userId").asString()).isEqualTo(member.userId().toString());
        assertThat(data.path("email").asString()).isEqualTo(email);
        assertThat(data.path("displayName").asString()).isEqualTo("Test Member");
        assertThat(data.path("householdId").asString())
            .isEqualTo(admin.householdId().toString());
        assertThat(data.path("role").asString()).isEqualTo("MEMBER");
        assertThat(data.path("totpEnabled").asBoolean()).isFalse();
        assertThat(data.path("currency").asString()).isEqualTo("CAD");
        assertThat(data.path("country").asString()).isEqualTo("CA");
        assertThat(data.path("timezone").asString()).isEqualTo("America/Toronto");
        assertThat(data.path("locale").asString()).isEqualTo("en-CA");
        assertThat(data.path("onboardingCompleted").asBoolean()).isFalse();

        HttpResponse<String> refreshed = postJson(appUrl("/v1/auth/refresh"),
            Json.object("refreshToken", member.refreshToken()), AuthMode.HEADER, "bearer");
        assertThat(refreshed.statusCode()).isEqualTo(200);
        JsonNode rotated = Json.data(refreshed.body());
        assertThat(rotated.path("accessToken").asString()).isNotBlank();
        assertThat(rotated.path("refreshToken").asString())
            .as("rotation on every use (§1.5)")
            .isNotBlank()
            .isNotEqualTo(member.refreshToken());
        assertThat(rotated.path("expiresIn").asInt()).isEqualTo(900);

        String newAccess = rotated.path("accessToken").asString();
        assertThat(get(appUrl("/v1/auth/me"), newAccess).statusCode()).isEqualTo(200);

        HttpResponse<String> loggedOut = postJson(appUrl("/v1/auth/logout"),
            Json.object("refreshToken", rotated.path("refreshToken").asString()));
        assertThat(loggedOut.statusCode()).isEqualTo(200);

        HttpResponse<String> afterLogout = postJson(appUrl("/v1/auth/refresh"),
            Json.object("refreshToken", rotated.path("refreshToken").asString()));
        assertThat(afterLogout.statusCode())
            .as("logout revokes the family, so the token it returned is dead")
            .isEqualTo(401);
        assertThat(get(appUrl("/v1/auth/me"), newAccess).statusCode())
            .as("and so is the access token issued from it — a signed statement is not a session, "
                + "and until the filter checked the family a logged-out token went on working "
                + "for the rest of its fifteen minutes")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("Cookie mode sets both __Host- cookies and returns {user} with no tokens")
    void cookieModeRoundTrip() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("cookie");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);

        String csrf = csrfToken();
        HttpResponse<String> login = loginCookieMode(email, MEMBER_PASSWORD);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);

        JsonNode data = Json.data(login.body());
        assertThat(data.isNull())
            .as("cookie-mode success never returns data: null (handoff point 5)")
            .isFalse();
        assertThat(data.path("user").path("email").asString()).isEqualTo(email);
        assertThat(data.has("accessToken")).isFalse();
        assertThat(data.has("refreshToken")).isFalse();

        assertThat(setCookies(login, AuthCookies.ACCESS_COOKIE)).singleElement()
            .satisfies(header -> assertThat(header)
                .contains("HttpOnly").contains("Secure").contains("SameSite=Lax")
                .contains("Path=/"));
        assertThat(setCookies(login, AuthCookies.REFRESH_COOKIE)).hasSize(1);

        String cookies = sessionCookies(login, csrf);
        HttpResponse<String> me = getWith(appUrl("/v1/auth/me"), "Cookie", cookies);
        assertThat(me.statusCode())
            .as("the access cookie authenticates without an Authorization header")
            .isEqualTo(200);
        assertThat(Json.data(me.body()).path("email").asString()).isEqualTo(email);
    }

    @Test
    @DisplayName("A cookie-mode refresh sends no body and gets no tokens back")
    void cookieModeRefreshUsesTheCookie() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("cookie-refresh");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);

        String csrf = csrfToken();
        HttpResponse<String> login = loginCookieMode(email, MEMBER_PASSWORD);
        String cookies = sessionCookies(login, csrf);

        // The web client posts a literal null body (apps/web/src/api/client.ts:229).
        HttpResponse<String> refreshed = postJson(appUrl("/v1/auth/refresh"), "null",
            "X-Requested-With", "XMLHttpRequest", "X-XSRF-TOKEN", csrf, "Cookie", cookies);

        assertThat(refreshed.statusCode()).as(refreshed.body()).isEqualTo(200);
        JsonNode data = Json.data(refreshed.body());
        assertThat(data.has("accessToken"))
            .as("echoing a rotated token into a readable body defeats the HttpOnly cookie the "
                + "client chose")
            .isFalse();
        assertThat(data.path("expiresIn").asInt()).isEqualTo(900);
        assertThat(setCookies(refreshed, AuthCookies.ACCESS_COOKIE)).hasSize(1);
        assertThat(setCookies(refreshed, AuthCookies.REFRESH_COOKIE)).hasSize(1);
    }

    @Test
    @DisplayName("GET /v1/auth/me is 401 anonymously and 401 with a garbage token")
    void meRequiresASession() {
        assertThat(get(appUrl("/v1/auth/me")).statusCode()).isEqualTo(401);
        assertThat(get(appUrl("/v1/auth/me"), "not.a.token").statusCode()).isEqualTo(401);

        String body = get(appUrl("/v1/auth/me")).body();
        assertThat(Json.parse(body).path("success").asBoolean()).isFalse();
        assertThat(Json.parse(body).path("error").path("status").asInt()).isEqualTo(401);
        assertThat(Json.parse(body).path("error").path("requestId").asString(""))
            .as("RequestIdMdcFilter runs with ERROR dispatch so a 401 body still carries one")
            .isNotBlank();
    }

    @Test
    @DisplayName("GET /v1/status stays bare while every /v1/auth/* body is enveloped")
    void statusIsTheOnlyBareRoute() {
        HttpResponse<String> status = get(appUrl("/v1/status"));
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(Json.parse(status.body()).has("success"))
            .as("§4.1: /v1/status is the one route with no envelope")
            .isFalse();
        assertThat(Json.parse(status.body()).has("status")).isTrue();

        HttpResponse<String> providers = get(appUrl("/v1/auth/providers"));
        assertThat(Json.parse(providers.body()).path("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("GET /v1/auth/providers is public and reports closed registration")
    void providersIsPublic() {
        HttpResponse<String> response = get(appUrl("/v1/auth/providers"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = Json.data(response.body());
        assertThat(data.path("registrationOpen").asBoolean())
            .as("closed by default (gap P2-7)")
            .isFalse();
        assertThat(data.path("local").asBoolean()).isTrue();
        assertThat(data.path("oidc").path("enabled").asBoolean()).isFalse();
        assertThat(data.path("oidc").has("displayName")).isTrue();
    }

    @Test
    @DisplayName("Registering without an invite is 403 registration-closed")
    void registrationIsClosedWithoutAnInvite() {
        HttpResponse<String> response = postJson(appUrl("/v1/auth/register"),
            Json.object("email", uniqueEmail("stranger"), "password", MEMBER_PASSWORD,
                "displayName", "Stranger", "currency", "CAD", "country", "CA",
                "timezone", "UTC"));

        assertThat(response.statusCode())
            .as("the web login page compares this slug by name, and only reaches the comparison "
                + "on a 403 (apps/web/src/pages/Login/errorCopy.ts:67)")
            .isEqualTo(403);
        assertThat(Json.errorCode(response.body())).isEqualTo("registration-closed");
    }

    @Test
    @DisplayName("A duplicate address is 400 with a field error on email")
    void duplicateAddressesAreAFieldError() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("duplicate");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);

        HttpResponse<String> second = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", createInvite(admin, null), "email", email,
                "password", MEMBER_PASSWORD, "displayName", "Again"));

        assertThat(second.statusCode()).isEqualTo(400);
        JsonNode fieldErrors = Json.parse(second.body()).path("error").path("fieldErrors");
        assertThat(Json.elements(fieldErrors))
            .as("the register form renders each field error under its input "
                + "(apps/web/src/pages/Register/RegisterForm.tsx:75)")
            .anySatisfy(error -> assertThat(error.path("field").asString()).isEqualTo("email"));
    }

    @Test
    @DisplayName("Probing for registered addresses meets the same gate as guessing passwords")
    void probingForRegisteredAddressesIsThrottled() {
        // B3-09. email-taken has to say what it says: a new address is signed straight in, so
        // the two answers cannot be made the same without a fake success that leaves somebody
        // believing they registered. What can be bounded is how often it is asked. Before
        // this, an open invite or open registration answered it 20 times a minute per address
        // for as long as anybody cared to ask, and never consumed the invite.
        Session admin = loginBootstrapAdmin();
        String taken = uniqueEmail("probed");
        registerWithInvite(createInvite(admin, null), taken, MEMBER_PASSWORD);
        String openInvite = createInvite(admin, null);
        String prober = "198.19.254.1";

        for (int i = 0; i < 20; i++) {
            HttpResponse<String> probe = postJson(appUrl("/v1/auth/register"),
                Json.object("inviteToken", openInvite, "email", taken,
                    "password", MEMBER_PASSWORD, "displayName", "Probe"),
                FORWARDED_FOR, prober);
            assertThat(probe.statusCode()).as(probe.body()).isEqualTo(400);
        }
        HttpResponse<String> refused = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", openInvite, "email", taken,
                "password", MEMBER_PASSWORD, "displayName", "Probe"),
            FORWARDED_FOR, prober);

        assertThat(refused.statusCode())
            .as("each email-taken counts as a failed sign-in from the address, so twenty of them "
                + "meet the per-address gate login guessing meets: %s", refused.body())
            .isEqualTo(429);
        assertThat(Json.errorCode(refused.body())).isEqualTo("account-locked");
    }

    @Test
    @DisplayName("A deny-listed password is 400 password-policy with a field error")
    void weakPasswordsAreRefusedWithAFieldError() {
        Session admin = loginBootstrapAdmin();

        HttpResponse<String> response = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", createInvite(admin, null), "email", uniqueEmail("weak"),
                "password", "password1234", "displayName", "Weak"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("password-policy");
        assertThat(Json.elements(Json.parse(response.body()).path("error").path("fieldErrors")))
            .anySatisfy(error -> assertThat(error.path("field").asString()).isEqualTo("password"));
    }

    @Test
    @DisplayName("A malformed body is 400, not 422 — the clients have no 422 branch")
    void validationFailuresAre400() {
        HttpResponse<String> response = withoutRequestValidation(
            "'not-an-email' fails LoginRequest.email's format and the empty password fails its "
                + "minLength — which is the point: the subject is the 400 the server answers",
            () -> postJson(appUrl("/v1/auth/login"),
                Json.object("email", "not-an-email", "password", "")));

        assertThat(response.statusCode())
            .as("apps/web's codeFromError has no 422 case, so a 422 would render 'Trove is "
                + "having trouble on its end' for a typo in an email box")
            .isEqualTo(400);
        assertThat(Json.parse(response.body()).path("error").path("fieldErrors").isArray())
            .as("fieldErrors is a LIST of {field,message}, not a map (web types.ts:44)")
            .isTrue();
    }

    @Test
    @DisplayName("Registration defaults to CAD/CA/UTC when the client omits them")
    void registrationDefaults() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("defaults");

        HttpResponse<String> response = postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", createInvite(admin, null), "email", email,
                "password", MEMBER_PASSWORD, "displayName", "Defaulted"),
            AuthMode.HEADER, "bearer");

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode user = Json.data(response.body()).path("user");
        HttpResponse<String> me = get(appUrl("/v1/auth/me"),
            Json.data(response.body()).path("accessToken").asString());
        JsonNode data = Json.data(me.body());
        assertThat(user.path("email").asString()).isEqualTo(email);
        assertThat(data.path("currency").asString()).isEqualTo("CAD");
        assertThat(data.path("country").asString()).isEqualTo("CA");
        assertThat(data.path("timezone").asString()).isEqualTo("UTC");
    }
}
