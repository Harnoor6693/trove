package finance.trove.auth.jwt;

import finance.trove.common.observability.RequestPath;
import finance.trove.common.exception.SecurityErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires {@code X-Requested-With: XMLHttpRequest} on mutating requests that authenticated
 * through the <strong>cookie</strong> ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §2.1).
 *
 * <p>It is a second, independent CSRF layer beside the token: browsers do not attach custom
 * headers to cross-origin form posts or to any request that has not survived a CORS preflight, so
 * the header's presence is cheap evidence that the request came from our own script rather than
 * from someone else's page.
 *
 * <p><strong>The change from the pre-collapse filter is the scoping, and it is a bug fix.</strong>
 * The gateway version enforced the header on <em>every</em> mutating request regardless of how
 * the caller authenticated, with a comment arguing that a bearer exemption would be bypassable.
 * That argument does not survive the {@code typ}-checked verifier: a garbage bearer string no
 * longer authenticates anything, so there is nothing to fall through to. What the unconditional
 * rule does do is 403 mobile's bearer-only {@code POST /v1/auth/login} the moment the mobile
 * client lands (§2.1, major). Enforcement is therefore keyed on
 * {@link AuthSource#ATTRIBUTE}, which {@link CookieOrBearerJwtAuthFilter} sets.
 *
 * <p>Anonymous mutating requests — login, register, refresh, the reset endpoints — are not
 * covered here either: they carry no ambient credential, so there is no session to ride. Spring's
 * own {@code CsrfFilter} still protects them with the double-submit token.
 */
public class CsrfRequiresHeaderFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(CsrfRequiresHeaderFilter.class);
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final String HEADER = "X-Requested-With";
    private static final String EXPECTED = "XMLHttpRequest";

    private final SecurityErrorWriter errorWriter;

    public CsrfRequiresHeaderFilter(SecurityErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean cookieAuthenticated =
            request.getAttribute(AuthSource.ATTRIBUTE) == AuthSource.COOKIE;

        if (cookieAuthenticated && MUTATING.contains(request.getMethod())
            && !EXPECTED.equals(request.getHeader(HEADER))) {
            LOG.warn("Blocked cookie-authenticated {} {} with no {} header",
                request.getMethod(), RequestPath.describe(request), HEADER);
            errorWriter.write(request, response, HttpStatus.FORBIDDEN, "csrf-header-missing",
                "This request must carry the " + HEADER + ": " + EXPECTED + " header.");
            return;
        }

        chain.doFilter(request, response);
    }
}
