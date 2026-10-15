package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.auth.session.RefreshProperties;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.common.exception.UnauthorizedException;
import finance.trove.common.time.UserClock;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Rotation, the reuse-grace window, and family revocation over HTTP (§1.5, §9 risk 10).
 *
 * <p><strong>No {@code @TestPropertySource} and no sleeping.</strong> Shrinking the grace window
 * with a property gave this class a Spring context of its own — a second full application boot in
 * a suite whose every other IT shares one — and still left the outside-the-window case racing a
 * one-second wall clock over real HTTP. The window is judged on the {@code now} the caller passes
 * in ({@code RefreshTokenService#rotate}), so the honest way to be outside it is to say so: the
 * replay below is driven through the service with an instant past {@code reuseGrace}, and its
 * consequence — the whole family dead — is then asserted over HTTP, which is the part only an
 * integration test can prove.
 */
class RefreshTokenIT extends AuthFlowSupport {

    @Autowired
    private RefreshTokenService refreshTokens;

    @Autowired
    private RefreshProperties refreshProperties;

    @Autowired
    private UserClock clock;

    private HttpResponse<String> refresh(String token) {
        return postJson(appUrl("/v1/auth/refresh"), Json.object("refreshToken", token),
            AuthMode.HEADER, "bearer");
    }

    @Test
    @DisplayName("Two simultaneous refreshes both succeed and receive the SAME successor")
    void concurrentRefreshesConverge() throws Exception {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "refresh-race");

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<HttpResponse<String>>> both = List.of(
                pool.submit(() -> {
                    start.await();
                    return refresh(member.refreshToken());
                }),
                pool.submit(() -> {
                    start.await();
                    return refresh(member.refreshToken());
                }));
            start.countDown();

            HttpResponse<String> first = both.get(0).get(30, TimeUnit.SECONDS);
            HttpResponse<String> second = both.get(1).get(30, TimeUnit.SECONDS);

            assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
            assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
            assertThat(Json.data(second.body()).path("refreshToken").asString())
                .as("two tabs that both hit a 401 and both refresh must converge on one token; "
                    + "forking the family means the loser looks like a thief on its next use")
                .isEqualTo(Json.data(first.body()).path("refreshToken").asString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Re-presenting a rotated token inside the grace window returns that successor")
    void replayInsideTheGraceWindowIsTolerated() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "refresh-grace");

        HttpResponse<String> first = refresh(member.refreshToken());
        HttpResponse<String> replay = refresh(member.refreshToken());

        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(Json.data(replay.body()).path("refreshToken").asString())
            .isEqualTo(Json.data(first.body()).path("refreshToken").asString());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE user_id = ? "
                + "AND type = 'REFRESH_REUSE_DETECTED'", Long.class, member.userId()))
            .as("an ordinary cross-tab race is not a security event")
            .isZero();
    }

    @Test
    @DisplayName("Re-presenting a rotated token AFTER the window revokes the whole family")
    void replayOutsideTheGraceWindowBurnsTheFamily() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "refresh-reuse");

        HttpResponse<String> rotated = refresh(member.refreshToken());
        assertThat(rotated.statusCode()).as(rotated.body()).isEqualTo(200);
        String successor = Json.data(rotated.body()).path("refreshToken").asString();

        // Past the window by one second, on the clock the decision is actually made against —
        // no sleep, no shortened property, and no second Spring context to hold that property.
        Instant afterTheWindow =
            clock.now().plus(refreshProperties.reuseGrace()).plusSeconds(1);
        assertThatThrownBy(() ->
            refreshTokens.rotate(member.refreshToken(), "127.0.0.1", "IT", afterTheWindow))
            .as("nobody honest still holds the old token")
            .isInstanceOf(UnauthorizedException.class)
            .hasMessage("Invalid or expired session");

        assertThat(refresh(successor).statusCode())
            .as("the successor is compromised too, so the whole family goes — and it goes over "
                + "HTTP, which is what proves the revoke survived the 401 that followed it "
                + "(SessionRevoker is REQUIRES_NEW for exactly this reason)")
            .isEqualTo(401);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE user_id = ? "
                + "AND type = 'REFRESH_REUSE_DETECTED'", Long.class, member.userId()))
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("A rotated access token reflects a role change made since the login")
    void rotationRereadsTheAccount() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "refresh-role");

        assertThat(get(appUrl("/v1/admin/invites"), member.accessToken()).statusCode())
            .isEqualTo(403);

        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", member.userId());
        String rotatedAccess = Json.data(refresh(member.refreshToken()).body())
            .path("accessToken").asString();

        assertThat(get(appUrl("/v1/admin/invites"), rotatedAccess).statusCode())
            .as("the access token is re-derived from the users row, so an admin who promotes or "
                + "demotes somebody does not have to wait out a session for it to mean anything")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("A deactivated account cannot refresh, even with a valid token")
    void deactivatedAccountsCannotRefresh() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "refresh-inactive");

        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", member.userId());

        assertThat(refresh(member.refreshToken()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Garbage, absent, revoked and burnt-family refusals are one identical answer")
    void everyRefusalLooksTheSame() {
        // The name promised identity across cases; the old body checked one literal against one
        // case. Every distinguishable refusal is an oracle: "revoked" tells whoever holds a stolen
        // token that it was real and that the theft was noticed, "unknown" that it never existed.
        Session loggedOut = inviteAndRegister(loginBootstrapAdmin(), "refresh-loggedout");
        assertThat(postJson(appUrl("/v1/auth/logout"),
            Json.object("refreshToken", loggedOut.refreshToken())).statusCode()).isEqualTo(200);

        Session burnt = inviteAndRegister(loginBootstrapAdmin(), "refresh-burnt");
        String successor = Json.data(refresh(burnt.refreshToken()).body())
            .path("refreshToken").asString();
        assertThatThrownBy(() -> refreshTokens.rotate(burnt.refreshToken(), "127.0.0.1", "IT",
            clock.now().plus(refreshProperties.reuseGrace()).plusSeconds(1)))
            .isInstanceOf(UnauthorizedException.class);

        Map<String, HttpResponse<String>> refusals = new LinkedHashMap<>();
        refusals.put("garbage", refresh("not-a-token"));
        refusals.put("absent", postJson(appUrl("/v1/auth/refresh"), "null"));
        refusals.put("revoked by logout", refresh(loggedOut.refreshToken()));
        refusals.put("family burnt by a detected replay", refresh(successor));

        Map<String, List<String>> shapes = new LinkedHashMap<>();
        refusals.forEach((label, response) -> {
            assertThat(response.statusCode())
                .as("%s: %s", label, response.body())
                .isEqualTo(401);
            var error = Json.parse(response.body()).path("error");
            shapes.put(label, List.of(
                error.path("type").asString(),
                error.path("title").asString(),
                error.path("detail").asString()));
        });

        assertThat(shapes.values().stream().distinct().toList())
            .as("type, title and detail must be identical across every refusal: %s", shapes)
            .hasSize(1);
        assertThat(shapes.values().iterator().next())
            .element(2)
            .isEqualTo("Invalid or expired session");
    }

    @Test
    @DisplayName("A failed cookie-mode refresh clears the cookies it could not renew")
    void aFailedCookieRefreshClearsTheCookies() {
        String csrf = csrfToken();
        HttpResponse<String> response = postJson(appUrl("/v1/auth/refresh"), "null",
            "X-Requested-With", "XMLHttpRequest", "X-XSRF-TOKEN", csrf,
            "Cookie", cookieHeader("XSRF-TOKEN", csrf,
                finance.trove.auth.jwt.AuthCookies.REFRESH_COOKIE, "a-token-nobody-issued"));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(setCookies(response, finance.trove.auth.jwt.AuthCookies.REFRESH_COOKIE))
            .as("a browser holding a revoked family would otherwise present it on every request "
                + "for the rest of the cookie's life, writing a reuse event each time")
            .isNotEmpty()
            .allSatisfy(header -> assertThat(header).contains("Max-Age=0"));
    }
}
