package finance.trove.auth.jwt;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;

/**
 * Owns every attribute of the two session cookies
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5).
 *
 * <p>The {@code __Host-} prefix is a browser-enforced invariant, not a naming convention: a
 * cookie with that prefix is only accepted if it is {@code Secure}, has {@code Path=/} and has no
 * {@code Domain} attribute. That rules out a subdomain of the install setting a cookie the app
 * would then trust, which is the practical attack against a cookie session on a shared domain.
 * There is deliberately no configuration knob to drop {@code Secure} — it would silently void the
 * prefix's guarantee.
 *
 * <p><strong>{@code SameSite} is {@code Lax}, not {@code Strict}</strong>. This is a change from
 * the pre-collapse gateway and it is load-bearing rather than cosmetic (§2.1 marks the class
 * REWRITE for exactly this): the OIDC flow returns the browser to this origin through a
 * cross-site redirect from the identity provider, and a {@code Strict} cookie is not sent on that
 * navigation — so the user lands logged out on a page that just logged them in. {@code Lax} still
 * withholds the cookie from cross-site {@code POST}s, and the CSRF token plus
 * {@code X-Requested-With} cover the rest.
 *
 * <p><strong>Dev caveat.</strong> Browsers drop {@code Secure} cookies on plain {@code http://}
 * except on {@code localhost}. Serving the SPA from a LAN IP over HTTP makes login appear to
 * succeed while every subsequent request is anonymous.
 */
public final class AuthCookies {

    public static final String ACCESS_COOKIE = "__Host-trove_access";
    public static final String REFRESH_COOKIE = "__Host-trove_refresh";

    /**
     * Carries the {@code typ=totp_challenge} token across the one navigation that has no client
     * code in the loop: the OIDC callback, which ends in a browser redirect to the login page
     * (§1.5). A query string would put a credential in the address bar, the browser history, the
     * referrer of every asset that page loads and the access log of anything in front of it; the
     * cookie is {@code HttpOnly}, so the challenge is never readable by script either.
     */
    public static final String TOTP_CHALLENGE_COOKIE = "__Host-trove_totp_challenge";

    /**
     * Cookie path. {@code /} rather than the {@code /api} servlet context path: the browser only
     * sends a cookie whose path prefixes the request path, and the SPA is served from {@code /}.
     * The same reasoning is why the CSRF cookie is pinned to {@code /} in {@code SecurityConfig}.
     */
    public static final String PATH = "/";

    private AuthCookies() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static void setAccessCookie(HttpServletResponse response, String jwt, Duration maxAge) {
        response.addHeader("Set-Cookie", build(ACCESS_COOKIE, jwt, maxAge.toSeconds()));
    }

    public static void setRefreshCookie(HttpServletResponse response, String token,
                                        Duration maxAge) {
        response.addHeader("Set-Cookie", build(REFRESH_COOKIE, token, maxAge.toSeconds()));
    }

    /**
     * Holds the OIDC half-login's TOTP challenge for as long as the challenge itself is valid.
     *
     * @param maxAge the challenge token's own lifetime; the cookie must not outlive the credential
     *               it carries, or a stale cookie would be presented at the next login attempt
     */
    public static void setTotpChallengeCookie(HttpServletResponse response, String jwt,
                                              Duration maxAge) {
        response.addHeader("Set-Cookie", build(TOTP_CHALLENGE_COOKIE, jwt, maxAge.toSeconds()));
    }

    /** Expires the challenge cookie; called once the second factor has been answered. */
    public static void clearTotpChallengeCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", build(TOTP_CHALLENGE_COOKIE, "", 0));
    }

    public static Optional<String> readTotpChallengeCookie(HttpServletRequest request) {
        return read(request, TOTP_CHALLENGE_COOKIE);
    }

    /** Expires both cookies. Used by logout and by a refresh that found a revoked family. */
    public static void clear(HttpServletResponse response) {
        response.addHeader("Set-Cookie", build(ACCESS_COOKIE, "", 0));
        response.addHeader("Set-Cookie", build(REFRESH_COOKIE, "", 0));
    }

    /**
     * Expires the access cookie only, leaving the refresh cookie in place so the SPA's
     * 401 → {@code /v1/auth/refresh} recovery path still works in the ordinary
     * access-expired-but-refresh-valid case.
     */
    public static void clearAccessCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", build(ACCESS_COOKIE, "", 0));
    }

    public static Optional<String> readAccessCookie(HttpServletRequest request) {
        return read(request, ACCESS_COOKIE);
    }

    public static Optional<String> readRefreshCookie(HttpServletRequest request) {
        return read(request, REFRESH_COOKIE);
    }

    private static String build(String name, String value, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path(PATH)
            .maxAge(maxAgeSeconds)
            .build()
            .toString();
    }

    private static Optional<String> read(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
            .filter(c -> name.equals(c.getName()))
            .map(Cookie::getValue)
            .filter(v -> v != null && !v.isBlank())
            .findFirst();
    }
}
