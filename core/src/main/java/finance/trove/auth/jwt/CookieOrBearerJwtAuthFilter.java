package finance.trove.auth.jwt;

import finance.trove.auth.session.SessionValidity;
import finance.trove.common.exception.UnauthorizedException;
import finance.trove.common.observability.RequestPath;
import finance.trove.users.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The monolith's single authentication filter: bearer header first, {@code __Host-trove_access}
 * cookie second ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.2).
 *
 * <p>It replaces both pre-collapse filters — the gateway's {@code CookieJwtAuthFilter} and
 * {@code libs/common}'s {@code JwtAuthFilter}, whose {@code /internal/**} bypass is deleted with
 * it: there are no internal service calls left to bypass anything for, and a path prefix that
 * skips authentication is exactly the M1 pattern §9 risk 1 exists to prevent.
 *
 * <p>Four behaviours worth stating:
 * <ul>
 *   <li><strong>A signature is not a session.</strong> The token's refresh family ({@code fid})
 *       is checked against {@code refresh_token} on every request, so a revoked session stops at
 *       the next call rather than at the next expiry. See {@code SessionValidity} for why that
 *       read is worth its cost.</li>
 *   <li><strong>The header wins.</strong> A caller that sets {@code Authorization} means it; a
 *       stale cookie must not silently override an explicit token.</li>
 *   <li><strong>A bad token is not a 401 from here.</strong> The filter clears the context and
 *       continues, so the request reaches the authorization rules and a <em>public</em> route
 *       still answers 200 with an expired cookie attached. The 401, when one is owed, is written
 *       by {@code TroveAuthenticationEntryPoint} in the shared error shape — the pre-collapse
 *       {@code JwtAuthFilter} wrote its own 401 body inline, which is how two different error
 *       shapes ended up on the wire.</li>
 *   <li><strong>It is not a {@code @Component}.</strong> A filter that is both a bean and a
 *       {@code Filter} is registered twice by Boot — the defect that ran every filter twice in
 *       seven services (§9 risk 8). It is constructed once, in {@code SecurityConfig}, and
 *       {@code ArchitectureTest} fails the build if this annotation ever reappears.</li>
 * </ul>
 */
public class CookieOrBearerJwtAuthFilter extends OncePerRequestFilter {

    private static final Logger LOG =
        LoggerFactory.getLogger(CookieOrBearerJwtAuthFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtVerifier verifier;
    private final SessionValidity sessions;

    public CookieOrBearerJwtAuthFilter(JwtVerifier verifier, SessionValidity sessions) {
        this.verifier = verifier;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Optional<String> bearer = extractBearer(request);
        Optional<String> cookie = bearer.isPresent()
            ? Optional.empty()
            : AuthCookies.readAccessCookie(request);
        Optional<String> token = bearer.or(() -> cookie);

        if (token.isPresent()) {
            AuthSource source = bearer.isPresent() ? AuthSource.BEARER : AuthSource.COOKIE;
            request.setAttribute(AuthSource.ATTRIBUTE, source);
            try {
                VerifiedToken verified = verifier.verify(token.get(), TokenType.ACCESS);
                if (!sessions.isLive(verified.familyId())) {
                    // A valid signature is not a live session. Every revocation in this
                    // application — logout, a password change or reset, a detected replay, a
                    // deleted account — writes to refresh_token rows, and until this check
                    // existed none of them reached the access token already in the attacker's
                    // hands: it went on authenticating every route for the rest of its fifteen
                    // minutes. Treated exactly like an expired token, so the refusal says
                    // nothing about why.
                    throw new UnauthorizedException("Invalid or expired token");
                }
                TroveUserDetails principal = new TroveUserDetails(
                    verified.userId(),
                    verified.householdId(),
                    verified.familyId(),
                    verified.email(),
                    verified.role() == null ? UserRole.MEMBER : UserRole.valueOf(verified.role()),
                    verified.steppedUpAt());
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
            } catch (UnauthorizedException e) {
                SecurityContextHolder.clearContext();
                LOG.debug("Rejected {} token on {} {}", source, request.getMethod(),
                    RequestPath.describe(request));
                if (cookie.isPresent()) {
                    // Only the access cookie: the refresh cookie is how the SPA recovers from
                    // exactly this state without sending the user back to the login page.
                    AuthCookies.clearAccessCookie(response);
                }
            } catch (IllegalArgumentException e) {
                // An unknown role name in a token this install signed: treat as unauthenticated
                // rather than defaulting the role, which would be a silent privilege decision.
                SecurityContextHolder.clearContext();
                LOG.warn("Token carried an unrecognised role claim; treating as anonymous");
            }
        }

        chain.doFilter(request, response);
    }

    private static Optional<String> extractBearer(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String value = header.substring(BEARER_PREFIX.length()).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }
}
