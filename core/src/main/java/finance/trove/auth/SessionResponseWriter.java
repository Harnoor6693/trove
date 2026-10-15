package finance.trove.auth;

import finance.trove.auth.dto.AuthUser;
import finance.trove.auth.dto.LoginResponse;
import finance.trove.auth.dto.RefreshResponse;
import finance.trove.auth.jwt.AuthCookies;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Turns a {@link LoginOutcome.Session} into the response the caller's {@link AuthMode} asked for.
 *
 * <p>It exists as its own component because the rule it enforces is easy to break by accident and
 * expensive when broken: <strong>in cookie mode no token ever appears in the body.</strong> The
 * whole point of an {@code HttpOnly} cookie is that a cross-site script cannot read the session;
 * echoing the same token into a JSON field that {@code fetch} can read hands it back. Having one
 * place that decides means a new endpoint cannot quietly get it wrong.
 *
 * <p>The second rule, from the handoff contract point 5: <strong>cookie-mode success still returns
 * an object</strong>, {@code {user}}, never {@code data: null}. The SPA's {@code unwrap()} throws
 * on a null {@code data} ({@code apps/web/src/api/client.ts:292-318}), so a "no content needed"
 * body would fail every login in the browser.
 */
@Component
public class SessionResponseWriter {

    /**
     * The cookie's max-age is the refresh token's lifetime, not the access token's.
     *
     * <p>Both cookies outlive the access token deliberately. If the access cookie expired with its
     * token the browser would stop sending it, the request would arrive anonymous, and the SPA's
     * 401-triggered refresh would never fire because a 401 on an anonymous request looks like
     * "not logged in" rather than "session stale". Letting a dead token be sent and rejected is
     * what makes the refresh path reachable.
     */
    public void writeSession(HttpServletResponse response, AuthMode mode,
                             LoginOutcome.Session session, Instant now) {
        if (mode.isCookie()) {
            Duration refreshMaxAge = Duration.between(now, session.refresh().expiresAt());
            AuthCookies.setAccessCookie(response, session.access().value(), refreshMaxAge);
            AuthCookies.setRefreshCookie(response, session.refresh().value(), refreshMaxAge);
        }
    }

    /** The body for a completed authentication. */
    public LoginResponse body(AuthMode mode, LoginOutcome.Session session, long expiresInSeconds) {
        AuthUser user = AuthUser.of(session.user(), session.totpEnabled());
        return mode.isCookie()
            ? LoginResponse.cookie(user)
            : LoginResponse.bearer(user, session.access().value(), session.refresh().value(),
                expiresInSeconds);
    }

    /** The body for a rotation; cookie mode gets the lifetime only, never the tokens. */
    public RefreshResponse refreshBody(AuthMode mode, LoginOutcome.Session session,
                                       long expiresInSeconds) {
        return mode.isCookie()
            ? RefreshResponse.cookie(expiresInSeconds)
            : RefreshResponse.bearer(session.access().value(), session.refresh().value(),
                expiresInSeconds);
    }

    /** Ends a browser session. Harmless for a bearer caller, which has no cookies to clear. */
    public void clear(HttpServletResponse response) {
        AuthCookies.clear(response);
    }
}
