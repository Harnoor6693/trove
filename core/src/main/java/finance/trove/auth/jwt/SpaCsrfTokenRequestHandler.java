package finance.trove.auth.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * The single-page-application CSRF handler ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §5.10).
 *
 * <p>Spring Security's default is {@link XorCsrfTokenRequestAttributeHandler}, which masks the
 * token per response as a BREACH mitigation. That works for server-rendered forms, where the
 * server writes the masked value into the HTML. It does not work for an XHR client: the SPA reads
 * the <em>raw</em> value out of the {@code XSRF-TOKEN} cookie and echoes it in
 * {@code X-XSRF-TOKEN}, and the XOR handler rejects a raw value because it expects a mask. Every
 * cookie-mode mutation would 403.
 *
 * <p>The published fix, which this class is, keeps the BREACH mitigation where it belongs and
 * removes it where it cannot work:
 * <ul>
 *   <li>{@link #handle} delegates to the XOR handler, so a token rendered into a response body
 *       (a form, a bootstrap script) is still masked;</li>
 *   <li>{@link #resolveCsrfTokenValue} uses the plain handler whenever the value arrived in a
 *       <em>header</em> — the XHR case — and the XOR handler when it arrived as a request
 *       parameter.</li>
 * </ul>
 * The result is that an attacker still cannot read the token cross-origin (same-origin policy
 * covers the cookie and the header), while the SPA's ordinary double-submit works.
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       Supplier<CsrfToken> csrfToken) {
        xor.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        boolean fromHeader = StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()));
        return fromHeader
            ? plain.resolveCsrfTokenValue(request, csrfToken)
            : xor.resolveCsrfTokenValue(request, csrfToken);
    }
}
