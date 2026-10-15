package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.auth.jwt.AuthCookies;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three cooperating CSRF pieces of §1.5 / §5.10, each of which is a blocker on its own.
 *
 * <p>The rule the whole file is about: <strong>CSRF applies to credentials the browser attaches by
 * itself, and to nothing else.</strong> Spring's default — every mutating request — would 403
 * mobile's bearer-only {@code POST /v1/auth/login} the moment the mobile client landed, which is
 * §2.1's major finding.
 */
class CsrfIT extends AuthFlowSupport {

    @Test
    @DisplayName("An anonymous GET sets a readable XSRF-TOKEN cookie at Path=/")
    void anonymousGetMaterialisesTheToken() {
        HttpResponse<String> response = get(appUrl("/v1/auth/providers"));

        assertThat(setCookies(response, "XSRF-TOKEN")).singleElement()
            .satisfies(header -> {
                assertThat(header)
                    .as("the SPA reads this value with document.cookie; HttpOnly would make the "
                        + "double-submit impossible")
                    .doesNotContain("HttpOnly");
                assertThat(header)
                    .as("the default is the servlet context path /api, and the SPA is served at "
                        + "/ — a context-path-scoped cookie is invisible to it and every "
                        + "cookie-mode mutation 403s (§1.5, blocker)")
                    .contains("Path=/")
                    .doesNotContain("Path=/api");
            });
    }

    @Test
    @DisplayName("The token cookie exists BEFORE the first login, with no bootstrap call")
    void theTokenExistsBeforeTheFirstLogin() {
        assertThat(cookieValue(get(appUrl("/v1/status")), "XSRF-TOKEN"))
            .as("since Spring Security 6 the token is deferred and only written if something "
                + "asks for it; nothing does on a plain GET. POST /v1/auth/login is itself a "
                + "protected mutation, so without CsrfCookieFilter the very first login 403s "
                + "against a cookie that was never written")
            .isNotNull();
    }

    @Test
    @DisplayName("A cookie-mode login WITH the token succeeds")
    void cookieModeLoginWithTheTokenSucceeds() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("csrf-ok");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);

        assertThat(loginCookieMode(email, MEMBER_PASSWORD).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("A cookie-mode login WITHOUT the token is 403")
    void cookieModeLoginWithoutTheTokenIsForbidden() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("csrf-missing");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);

        HttpResponse<String> response = postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", MEMBER_PASSWORD),
            AuthMode.HEADER, "cookie", "X-Requested-With", "XMLHttpRequest");

        assertThat(response.statusCode())
            .as("login CSRF — logging a victim into the attacker's account — is only stopped by "
                + "the token, so the browser's login is protected even though it is anonymous")
            .isEqualTo(403);
        assertThat(Json.parse(response.body()).path("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("A cookie-mode login WITHOUT X-Requested-With still succeeds")
    void anonymousCookieModeLoginDoesNotNeedTheRequestedWithHeader() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("csrf-anon-xrw");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);
        String csrf = csrfToken();

        HttpResponse<String> response = postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", MEMBER_PASSWORD),
            AuthMode.HEADER, "cookie",
            "X-XSRF-TOKEN", csrf,
            "Cookie", cookieHeader("XSRF-TOKEN", csrf));

        assertThat(response.statusCode())
            .as("the two CSRF rules have two different triggers and the contract has to state "
                + "them separately. The double-submit token is demanded of anything declaring "
                + "cookie mode, this login included; X-Requested-With is demanded by "
                + "CsrfRequiresHeaderFilter only once a request has AUTHENTICATED through the "
                + "cookie (AuthSource.COOKIE), which an anonymous login cannot have done. "
                + "Demanding it here would describe a stricter server than the one that ships — "
                + "and would make a client that trusted the document fail against it")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("A bearer POST with no CSRF token at all succeeds — the mobile case")
    void bearerRequestsSkipCsrfEntirely() {
        Session admin = loginBootstrapAdmin();

        HttpResponse<String> anonymous = postJson(appUrl("/v1/auth/login"),
            Json.object("email", BOOTSTRAP_ADMIN_EMAIL, "password", BOOTSTRAP_ADMIN_PASSWORD),
            AuthMode.HEADER, "bearer", "X-Requested-With", "XMLHttpRequest");
        assertThat(anonymous.statusCode())
            .as("mobile sends the bearer mode header and X-Requested-With on every request and "
                + "has no cookie jar; it must not need a token it cannot obtain")
            .isEqualTo(200);

        HttpResponse<String> authenticated = postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(authenticated.statusCode())
            .as("an Authorization header cannot be set by a third-party page, so there is "
                + "nothing to ride")
            .isEqualTo(201);
    }

    @Test
    @DisplayName("A cookie-authenticated mutation without X-Requested-With is 403")
    void cookieAuthenticatedMutationsNeedTheRequestedWithHeader() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("csrf-xrw");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);
        String csrf = csrfToken();
        HttpResponse<String> login = loginCookieMode(email, MEMBER_PASSWORD);
        String cookies = sessionCookies(login, csrf);

        HttpResponse<String> withoutHeader = putJson(appUrl("/v1/me"),
            Json.object("displayName", "Renamed"),
            "X-XSRF-TOKEN", csrf, "Cookie", cookies);

        assertThat(withoutHeader.statusCode()).isEqualTo(403);
        assertThat(Json.errorCode(withoutHeader.body()))
            .as("a browser will not attach a custom header to a cross-origin form post or to any "
                + "request that has not survived a preflight, so its presence is cheap evidence "
                + "the request came from our own script")
            .isEqualTo("csrf-header-missing");

        HttpResponse<String> withHeader = putJson(appUrl("/v1/me"),
            Json.object("displayName", "Renamed"),
            "X-Requested-With", "XMLHttpRequest", "X-XSRF-TOKEN", csrf, "Cookie", cookies);
        assertThat(withHeader.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("A cookie-authenticated mutation without the token is 403")
    void cookieAuthenticatedMutationsNeedTheToken() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("csrf-token");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);
        String csrf = csrfToken();
        HttpResponse<String> login = loginCookieMode(email, MEMBER_PASSWORD);

        HttpResponse<String> response = putJson(appUrl("/v1/me"),
            Json.object("displayName", "Renamed"),
            "X-Requested-With", "XMLHttpRequest",
            "Cookie", cookieHeader(
                AuthCookies.ACCESS_COOKIE, cookieValue(login, AuthCookies.ACCESS_COOKIE)));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("The SPA's raw cookie value is accepted, not only Spring's masked form")
    void theRawCookieValueIsAccepted() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("csrf-raw");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);

        String csrf = csrfToken();
        HttpResponse<String> response = postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", MEMBER_PASSWORD),
            browserHeaders(csrf, cookieHeader("XSRF-TOKEN", csrf)));

        assertThat(response.statusCode())
            .as("Spring Security 7's default XorCsrfTokenRequestAttributeHandler rejects the raw "
                + "value an XHR client echoes back; SpaCsrfTokenRequestHandler is what accepts it")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("A safe method is never checked, even with a session cookie")
    void safeMethodsAreNeverChecked() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("csrf-safe");
        registerWithInvite(createInvite(admin, null), email, MEMBER_PASSWORD);
        String csrf = csrfToken();
        HttpResponse<String> login = loginCookieMode(email, MEMBER_PASSWORD);

        HttpResponse<String> response = getWith(appUrl("/v1/auth/me"), "Cookie", cookieHeader(
            AuthCookies.ACCESS_COOKIE, cookieValue(login, AuthCookies.ACCESS_COOKIE)));

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
