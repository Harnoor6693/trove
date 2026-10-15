package finance.trove.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Every attribute on the two session cookies is load-bearing, so every attribute is asserted.
 *
 * <p>{@code SameSite=Lax} in particular is a deliberate change from the pre-collapse gateway's
 * {@code Strict} (§2.1 marks {@code AuthCookies} REWRITE for exactly this): the OIDC flow returns
 * the browser to this origin through a cross-site redirect, and a Strict cookie is not sent on
 * that navigation — the user lands logged out on the page that just logged them in.
 */
class AuthCookiesTest {

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private String header(String cookieName) {
        return response.getHeaders("Set-Cookie").stream()
            .filter(value -> value.startsWith(cookieName + "="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no Set-Cookie for " + cookieName));
    }

    @Test
    @DisplayName("The access cookie is __Host-, HttpOnly, Secure, SameSite=Lax, Path=/")
    void accessCookieAttributes() {
        AuthCookies.setAccessCookie(response, "the.jwt.value", Duration.ofHours(24));
        String header = header(AuthCookies.ACCESS_COOKIE);

        assertThat(AuthCookies.ACCESS_COOKIE)
            .as("the __Host- prefix is browser-enforced: no subdomain of the install can set a "
                + "cookie this application would then trust")
            .startsWith("__Host-");
        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("Secure");
        assertThat(header).contains("SameSite=Lax");
        assertThat(header).contains("Path=/");
        assertThat(header).doesNotContain("Domain=");
        assertThat(header).contains("Max-Age=86400");
    }

    @Test
    @DisplayName("SameSite is Lax and not Strict, so the OIDC redirect lands logged in")
    void sameSiteIsLax() {
        AuthCookies.setRefreshCookie(response, "refresh", Duration.ofHours(24));
        assertThat(header(AuthCookies.REFRESH_COOKIE))
            .contains("SameSite=Lax")
            .doesNotContain("SameSite=Strict");
    }

    @Test
    @DisplayName("clear() expires both cookies; clearAccessCookie() leaves refresh alone")
    void clearing() {
        AuthCookies.clear(response);
        assertThat(header(AuthCookies.ACCESS_COOKIE)).contains("Max-Age=0");
        assertThat(header(AuthCookies.REFRESH_COOKIE)).contains("Max-Age=0");

        MockHttpServletResponse second = new MockHttpServletResponse();
        AuthCookies.clearAccessCookie(second);
        List<String> headers = second.getHeaders("Set-Cookie");
        assertThat(headers)
            .as("the refresh cookie is how the SPA recovers from a stale access token without "
                + "sending the user back to the login page")
            .hasSize(1);
        assertThat(headers.get(0)).startsWith(AuthCookies.ACCESS_COOKIE + "=");
    }

    @Test
    @DisplayName("Reading finds the right cookie among others, and empty when absent")
    void reading() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(
            new Cookie("unrelated", "x"),
            new Cookie(AuthCookies.ACCESS_COOKIE, "access-value"),
            new Cookie(AuthCookies.REFRESH_COOKIE, "refresh-value"));

        assertThat(AuthCookies.readAccessCookie(request)).contains("access-value");
        assertThat(AuthCookies.readRefreshCookie(request)).contains("refresh-value");
        assertThat(AuthCookies.readAccessCookie(new MockHttpServletRequest())).isEmpty();
    }
}
