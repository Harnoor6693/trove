package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.auth.jwt.AuthCookies;
import finance.trove.auth.totp.TotpGenerator;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The whole second-factor lifecycle over HTTP (readiness gap P2-6).
 *
 * <p>Enrol behind a password, confirm with a live code, get ten recovery codes once, log in
 * through the challenge, spend a recovery code, and remove the factor behind a full step-up.
 * Codes are computed here with the same {@code TotpGenerator} the server uses, which is safe to do
 * because {@code TotpGeneratorTest} pins that class against RFC 6238's published vectors — a
 * round-trip against a wrong implementation would otherwise pass happily.
 */
class TotpFlowIT extends AuthFlowSupport {

    private final TotpGenerator generator = new TotpGenerator();

    private String codeFor(String secret) {
        return codeFor(secret, 0);
    }

    /**
     * A code for {@code offset} steps ahead of now.
     *
     * <p>Needed because the replay guard is real: {@code confirmEnrolment} records the step it
     * accepted, and the same six digits inside that thirty-second window are then refused — which
     * is the point of {@code totp_credential.last_used_step} (gap P2-6). A test that enrols and
     * immediately logs in lands in the same window, so it asks for the NEXT step instead of
     * sleeping thirty seconds. Offset 1 is inside the +/-1 drift the server accepts, so this is
     * a legitimate code and not a widened window.
     */
    private String codeFor(String secret, int offset) {
        return generator.codeAt(secret, generator.timeStep(Instant.now(), 30) + offset, 6);
    }

    private record Enrolled(Session session, String secret, List<String> recoveryCodes) {
    }

    private Enrolled enrol(String prefix) {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, prefix);

        HttpResponse<String> setup = postJson(appUrl("/v1/auth/totp/setup"),
            Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(setup.statusCode()).as(setup.body()).isEqualTo(200);
        JsonNode enrolment = Json.data(setup.body());
        String secret = enrolment.path("secret").asString();
        assertThat(secret).isNotBlank();
        assertThat(enrolment.path("otpauthUri").asString()).contains("secret=" + secret);

        HttpResponse<String> confirm = postJson(appUrl("/v1/auth/totp/confirm"),
            Json.object("code", codeFor(secret)),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(confirm.statusCode()).as(confirm.body()).isEqualTo(200);
        List<String> codes = Json.elements(Json.data(confirm.body()).path("recoveryCodes")).stream()
            .map(node -> node.asString()).toList();

        return new Enrolled(member, secret, codes);
    }

    @Test
    @DisplayName("Enrolment does not enable the factor until a live code confirms it")
    void enrolmentRequiresConfirmation() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "totp-unconfirmed");

        HttpResponse<String> setup = postJson(appUrl("/v1/auth/totp/setup"),
            Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(setup.statusCode()).isEqualTo(200);

        assertThat(Json.data(get(appUrl("/v1/auth/me"), member.accessToken()).body())
            .path("totpEnabled").asBoolean())
            .as("a user who scans a QR badly must not be enrolled into a factor that does not "
                + "work — there is no support desk and no email reset")
            .isFalse();

        HttpResponse<String> login = postJson(appUrl("/v1/auth/login"),
            Json.object("email", member.email(), "password", MEMBER_PASSWORD));
        assertThat(Json.data(login.body()).has("challengeToken"))
            .as("an unconfirmed enrolment must not start demanding codes")
            .isFalse();
    }

    @Test
    @DisplayName("Enrolment needs the password, not just the session")
    void enrolmentNeedsAFreshPassword() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "totp-nopassword");

        HttpResponse<String> response = postJson(appUrl("/v1/auth/totp/setup"),
            Json.object("password", "the-wrong-passphrase"),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode())
            .as("403 and not 401: the session is fine, so a 401 would send the client's "
                + "interceptor off to refresh a perfectly valid session and retry into the "
                + "same refusal")
            .isEqualTo(403);
        assertThat(Json.errorCode(response.body())).isEqualTo("step-up-required");
    }

    @Test
    @DisplayName("Login returns a challenge, and /login/totp completes it")
    void loginGoesThroughTheChallenge() {
        Enrolled enrolled = enrol("totp-login");

        HttpResponse<String> login = postJson(appUrl("/v1/auth/login"),
            Json.object("email", enrolled.session().email(), "password", MEMBER_PASSWORD));

        assertThat(login.statusCode())
            .as("a second factor being owed is a 200 SUCCESS carrying challengeToken, not an "
                + "error status — the clients discriminate inside a successful envelope "
                + "(apps/web/src/pages/Login/index.tsx:58)")
            .isEqualTo(200);
        JsonNode data = Json.data(login.body());
        String challenge = data.path("challengeToken").asString();
        assertThat(challenge).isNotBlank();
        assertThat(data.has("user")).isFalse();
        assertThat(data.has("accessToken")).isFalse();

        HttpResponse<String> stepUp = postJson(appUrl("/v1/auth/login/totp"),
            Json.object("challengeToken", challenge, "code", codeFor(enrolled.secret(), 1)));

        assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(200);
        JsonNode session = Json.data(stepUp.body());
        assertThat(session.path("accessToken").asString()).isNotBlank();
        assertThat(session.path("user").path("totpEnabled").asBoolean()).isTrue();

        assertThat(get(appUrl("/v1/auth/me"), session.path("accessToken").asString())
            .statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("The challenge cookie alone completes the login, and is cleared by that response")
    void theChallengeCookieCompletesTheLoginAndIsThenCleared() {
        Enrolled enrolled = enrol("totp-cookie");
        String csrf = csrfToken();
        String cookies = cookieHeader("XSRF-TOKEN", csrf);
        HttpResponse<String> login = postJson(appUrl("/v1/auth/login"),
            Json.object("email", enrolled.session().email(), "password", MEMBER_PASSWORD),
            browserHeaders(csrf, cookies));
        String challenge = Json.data(login.body()).path("challengeToken").asString();
        assertThat(challenge).isNotBlank();

        // What the OIDC half-login leaves a browser: the token in __Host-trove_totp_challenge and
        // nothing in client code's hands, so the body carries the code alone.
        HttpResponse<String> stepUp = postJson(appUrl("/v1/auth/login/totp"),
            Json.object("code", codeFor(enrolled.secret(), 1)),
            browserHeaders(csrf, cookieHeader("XSRF-TOKEN", csrf,
                AuthCookies.TOTP_CHALLENGE_COOKIE, challenge)));

        assertThat(stepUp.statusCode())
            .as("the cookie is the only carrier the OIDC second factor has — no client code ever "
                + "sees that challenge (%s)", stepUp.body())
            .isEqualTo(200);
        assertThat(Json.data(stepUp.body()).path("user").path("totpEnabled").asBoolean()).isTrue();
        assertThat(cookieValue(stepUp, AuthCookies.ACCESS_COOKIE))
            .as("cookie mode: the session comes back in Set-Cookie, not in the body")
            .isNotNull();
        assertThat(setCookies(stepUp, AuthCookies.TOTP_CHALLENGE_COOKIE))
            .as("the challenge has been spent, and a spent challenge left in the browser is a "
                + "live 60-second credential that can only produce a confusing failure at the "
                + "next login. This is the SetSessionCookiesClearingChallenge header the "
                + "contract declares on this 200")
            .singleElement()
            .satisfies(header -> assertThat(header).contains("Max-Age=0"));
    }

    @Test
    @DisplayName("An explicit challenge token beats a stale cookie from another attempt")
    void theBodyTokenWinsOverTheCookie() {
        Enrolled abandoned = enrol("totp-stale");
        Enrolled current = enrol("totp-current");
        String csrf = csrfToken();
        String cookies = cookieHeader("XSRF-TOKEN", csrf);

        String staleChallenge = Json.data(postJson(appUrl("/v1/auth/login"),
            Json.object("email", abandoned.session().email(), "password", MEMBER_PASSWORD),
            browserHeaders(csrf, cookies)).body()).path("challengeToken").asString();
        String liveChallenge = Json.data(postJson(appUrl("/v1/auth/login"),
            Json.object("email", current.session().email(), "password", MEMBER_PASSWORD),
            browserHeaders(csrf, cookies)).body()).path("challengeToken").asString();

        HttpResponse<String> stepUp = postJson(appUrl("/v1/auth/login/totp"),
            Json.object("challengeToken", liveChallenge, "code", codeFor(current.secret(), 1)),
            browserHeaders(csrf, cookieHeader("XSRF-TOKEN", csrf,
                AuthCookies.TOTP_CHALLENGE_COOKIE, staleChallenge)));

        assertThat(stepUp.statusCode())
            .as("body first, cookie second: read the other way round, an abandoned OIDC attempt "
                + "left in the cookie jar would decide which account this code is checked "
                + "against (%s)", stepUp.body())
            .isEqualTo(200);
        assertThat(Json.data(stepUp.body()).path("user").path("email").asString())
            .as("the session must belong to the account the explicit token named")
            .isEqualTo(current.session().email());
    }

    @Test
    @DisplayName("A wrong code at the challenge is 401 and issues no session")
    void wrongChallengeCodeIsRefused() {
        Enrolled enrolled = enrol("totp-wrong");
        String challenge = Json.data(postJson(appUrl("/v1/auth/login"),
            Json.object("email", enrolled.session().email(), "password", MEMBER_PASSWORD)).body())
            .path("challengeToken").asString();

        HttpResponse<String> response = postJson(appUrl("/v1/auth/login/totp"),
            Json.object("challengeToken", challenge, "code", "000000"));

        assertThat(response.statusCode())
            .as("web maps 400 and 401 in the totp context onto 'that code was not accepted'")
            .isEqualTo(401);
        assertThat(Json.parse(response.body()).path("data").isMissingNode()
            || Json.parse(response.body()).path("data").isNull()).isTrue();
    }

    @Test
    @DisplayName("A recovery code completes a login and is then spent")
    void recoveryCodesWorkOnceAtLogin() {
        Enrolled enrolled = enrol("totp-recovery");
        assertThat(enrolled.recoveryCodes()).hasSize(10);
        String recovery = enrolled.recoveryCodes().get(0);

        String firstChallenge = Json.data(postJson(appUrl("/v1/auth/login"),
            Json.object("email", enrolled.session().email(), "password", MEMBER_PASSWORD)).body())
            .path("challengeToken").asString();
        HttpResponse<String> used = postJson(appUrl("/v1/auth/login/totp"),
            Json.object("challengeToken", firstChallenge, "code", recovery));
        assertThat(used.statusCode()).as(used.body()).isEqualTo(200);

        String secondChallenge = Json.data(postJson(appUrl("/v1/auth/login"),
            Json.object("email", enrolled.session().email(), "password", MEMBER_PASSWORD)).body())
            .path("challengeToken").asString();
        assertThat(postJson(appUrl("/v1/auth/login/totp"),
            Json.object("challengeToken", secondChallenge, "code", recovery)).statusCode())
            .as("a reusable recovery code is a permanent password that bypasses the factor")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("Removing the factor needs the password AND a current code")
    void disableNeedsAFullStepUp() {
        Enrolled enrolled = enrol("totp-disable");
        String bearer = "Bearer " + enrolled.session().accessToken();

        assertThat(delete(appUrl("/v1/auth/totp"),
            Json.object("password", "the-wrong-passphrase", "code", codeFor(enrolled.secret(), 1)),
            "Authorization", bearer).statusCode())
            .as("whoever has the unlocked device but not the password must not be able to "
                + "remove the factor")
            .isEqualTo(403);

        assertThat(delete(appUrl("/v1/auth/totp"),
            Json.object("password", MEMBER_PASSWORD, "code", "000000"),
            "Authorization", bearer).statusCode())
            .as("whoever has the password but not the phone must not either")
            .isEqualTo(403);

        HttpResponse<String> removed = delete(appUrl("/v1/auth/totp"),
            Json.object("password", MEMBER_PASSWORD, "code", codeFor(enrolled.secret(), 1)),
            "Authorization", bearer);
        assertThat(removed.statusCode()).as(removed.body()).isEqualTo(200);

        assertThat(Json.data(get(appUrl("/v1/auth/me"), enrolled.session().accessToken()).body())
            .path("totpEnabled").asBoolean()).isFalse();
        assertThat(Json.data(postJson(appUrl("/v1/auth/login"),
            Json.object("email", enrolled.session().email(), "password", MEMBER_PASSWORD)).body())
            .has("challengeToken")).isFalse();
    }

    @Test
    @DisplayName("Re-enrolling over a working factor is refused")
    void reEnrolmentIsRefused() {
        Enrolled enrolled = enrol("totp-reenrol");

        HttpResponse<String> response = postJson(appUrl("/v1/auth/totp/setup"),
            Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + enrolled.session().accessToken());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(response.body())).isEqualTo("totp-already-enabled");
    }

    @Test
    @DisplayName("An admin can clear a member's factor; the row records who did it")
    void adminCanClearAMembersFactor() {
        Session admin = loginBootstrapAdmin();
        Enrolled enrolled = enrol("totp-adminclear");

        HttpResponse<String> cleared = delete(
            appUrl("/v1/admin/users/" + enrolled.session().userId() + "/totp"), null,
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForList(
            "SELECT actor_id FROM security_event WHERE user_id = ? AND type = 'TOTP_DISABLE'",
            enrolled.session().userId()))
            .as("'the factor was removed FROM this account' and 'this account removed its own' "
                + "are different stories, which is why actor_id is a separate column")
            .isNotEmpty()
            .allSatisfy(row -> assertThat(row.get("actor_id")).isEqualTo(admin.userId()));
    }

    @Test
    @DisplayName("A member cannot clear another member's factor — that route is ADMIN only")
    void membersCannotClearOtherFactors() {
        Session admin = loginBootstrapAdmin();
        Enrolled enrolled = enrol("totp-notadmin");
        Session other = inviteAndRegister(admin, "totp-bystander");

        assertThat(delete(appUrl("/v1/admin/users/" + enrolled.session().userId() + "/totp"),
            null, "Authorization", "Bearer " + other.accessToken()).statusCode())
            .isEqualTo(403);
    }
}
