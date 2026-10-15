package finance.trove.auth.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Forces the deferred CSRF token to be materialised — and therefore its cookie to be written — on
 * every request, including anonymous ones ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §5.1,
 * §5.10).
 *
 * <p>Without this filter the SPA cannot log in at all, which is why the plan's review log records
 * it as a blocker found independently by two reviewers. Since Spring Security 6 the CSRF token is
 * <em>deferred</em>: {@code CsrfFilter} puts a {@code Supplier<CsrfToken>} on the request and only
 * generates and saves a token if something actually calls it. Nothing does on a plain
 * {@code GET}. But {@code POST /v1/auth/login} is itself a CSRF-protected mutating request, so the
 * very first thing the SPA does needs a token cookie that no earlier response ever set — and it
 * 403s.
 *
 * <p>Calling {@link CsrfToken#getToken()} here is the whole mechanism: it resolves the supplier,
 * which causes {@code CookieCsrfTokenRepository} to write {@code XSRF-TOKEN} at {@code Path=/}
 * where the SPA's XHR layer can read it back into the {@code X-XSRF-TOKEN} header.
 *
 * <p>It runs <em>after</em> Spring's {@code CsrfFilter}, not before it as §5.1's filter list reads
 * — the request attribute it resolves does not exist until {@code CsrfFilter} has set it. The
 * ordering intent of §5.1 (the cookie exists by the time the response is written) is preserved;
 * only the insertion point differs.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute instanceof CsrfToken token) {
            // Resolving the supplier is the point; the value itself is not used here.
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
