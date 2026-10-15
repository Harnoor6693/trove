package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.auth.AuthMode;
import finance.trove.auth.jwt.AuthCookies;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Which requests carry an ambient credential, and therefore need the double-submit token.
 *
 * <p>Spring's default is "every mutating request", which would 403 mobile's bearer-only
 * {@code POST /v1/auth/login} the moment the mobile client landed — the major finding of §2.1.
 * The narrower rule below states the actual threat model: CSRF is about credentials a browser
 * attaches by itself, and a caller that had to write an {@code Authorization} header in code has
 * nothing a third-party page could ride.
 */
class CsrfProtectionMatcherTest {

    private final RequestMatcher matcher = SecurityConfig.csrfProtected();

    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/auth/login");
        return request;
    }

    @ParameterizedTest(name = "{0} is never CSRF-checked")
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "TRACE"})
    @DisplayName("Safe methods are never checked")
    void safeMethodsAreExempt(String method) {
        MockHttpServletRequest request = request(method);
        request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "token"));

        assertThat(matcher.matches(request)).isFalse();
    }

    @Test
    @DisplayName("A cookie-authenticated mutation is checked")
    void cookieAuthenticatedMutationsAreChecked() {
        MockHttpServletRequest request = request("POST");
        request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "token"));

        assertThat(matcher.matches(request)).isTrue();
    }

    @Test
    @DisplayName("A request carrying only the refresh cookie is still checked")
    void refreshCookieAloneIsEnough() {
        MockHttpServletRequest request = request("POST");
        request.setCookies(new Cookie(AuthCookies.REFRESH_COOKIE, "token"));

        assertThat(matcher.matches(request))
            .as("the SPA's refresh call carries only the refresh cookie and is exactly the kind "
                + "of ambient-credential request CSRF exists for")
            .isTrue();
    }

    @Test
    @DisplayName("A browser's anonymous login is checked, because login CSRF is a real attack")
    void cookieModeLoginIsCheckedEvenWithNoCookie() {
        MockHttpServletRequest request = request("POST");
        request.addHeader(AuthMode.HEADER, "cookie");

        assertThat(matcher.matches(request))
            .as("logging a victim into the attacker's account is only stopped by the token")
            .isTrue();
    }

    @Test
    @DisplayName("A bearer-authenticated mutation is NOT checked")
    void bearerRequestsAreExempt() {
        MockHttpServletRequest request = request("POST");
        request.addHeader("Authorization", "Bearer some.jwt.value");

        assertThat(matcher.matches(request)).isFalse();
    }

    @Test
    @DisplayName("An explicit Authorization header wins over a stale cookie")
    void bearerBeatsACookie() {
        MockHttpServletRequest request = request("POST");
        request.addHeader("Authorization", "Bearer some.jwt.value");
        request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "stale"));

        assertThat(matcher.matches(request))
            .as("this mirrors CookieOrBearerJwtAuthFilter, where the header also wins")
            .isFalse();
    }

    @Test
    @DisplayName("A bearer-mode anonymous login is NOT checked — this is the mobile case")
    void mobileLoginIsExempt() {
        MockHttpServletRequest request = request("POST");
        request.addHeader(AuthMode.HEADER, "bearer");

        assertThat(matcher.matches(request))
            .as("the unconditional rule would 403 mobile's very first request (§2.1, major)")
            .isFalse();
    }

    @Test
    @DisplayName("A mutation with no credential and no mode header is not checked")
    void anonymousBearerDefaultIsExempt() {
        assertThat(matcher.matches(request("POST"))).isFalse();
        assertThat(matcher.matches(request("DELETE"))).isFalse();
    }

    @Test
    @DisplayName("A non-Bearer Authorization scheme does not buy the exemption")
    void onlyBearerSchemeIsExempt() {
        MockHttpServletRequest request = request("POST");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "token"));

        assertThat(matcher.matches(request)).isTrue();
    }

    @ParameterizedTest(name = "{0} with a session cookie is checked")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("Every mutating method is covered, not just POST")
    void everyMutatingMethod(String method) {
        MockHttpServletRequest request = request(method);
        request.setCookies(new Cookie(AuthCookies.ACCESS_COOKIE, "token"));

        assertThat(matcher.matches(request)).isTrue();
    }
}
