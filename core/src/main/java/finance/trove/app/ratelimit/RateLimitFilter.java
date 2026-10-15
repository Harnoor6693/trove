package finance.trove.app.ratelimit;

import finance.trove.app.SecurityConfig;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.exception.SecurityErrorWriter;
import finance.trove.common.net.ClientIpResolver;
import finance.trove.common.observability.RequestPath;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * The request-rate ceiling ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.1, §5.8; gap P2-5).
 *
 * <p>Registered by {@code SecurityConfig} <strong>after</strong>
 * {@code CookieOrBearerJwtAuthFilter}, which is what lets it key on a principal at all. The
 * original §5.1 draft put it before, and §5.1 records why that is a blocker: every authenticated
 * request would fall into one flat anonymous bucket and the per-route rows V12 seeds would be
 * unreachable.
 *
 * <h2>Three behaviours that are corrections of the source filter, not preferences</h2>
 * <ul>
 *   <li><strong>The address is never parsed by hand.</strong> The gateway filter read
 *       {@code X-Forwarded-For.split(",")[0]}, so a caller chose its own bucket by prefixing a
 *       hop. The address comes from {@link ClientIpResolver}, i.e. from the socket after the
 *       trusted-proxy valve, and from nowhere else.</li>
 *   <li><strong>{@code /v1/auth/**} fails CLOSED.</strong> The gateway filter caught every
 *       exception and let the request through with an ERROR line reading "failing open", which
 *       turned an outage of the counting store into a way of switching brute-force protection off
 *       on the sign-in surface. Here, a limiter that cannot decide refuses the sign-in surface
 *       with <strong>503</strong> {@code service-unavailable} — the request was never processed,
 *       so it is safe to repeat, and 503 says "the server is broken", which is true, where 429
 *       would blame the caller.</li>
 *   <li><strong>Everything else fails open,</strong> and that asymmetry is the whole point: on the
 *       ledger surface a limiter fault should not lock a household out of its own money, and there
 *       is no credential to guess at behind those routes. The fault is logged at ERROR either
 *       way.</li>
 * </ul>
 *
 * <h2>The path is decoded before anything looks at it</h2>
 * {@link HttpServletRequest#getRequestURI()} is the URI as the client spelled it, percent-escapes
 * intact and never decoded (the servlet contract). Spring routes on the <em>decoded</em> path, so
 * measuring a rule against the raw one lets a client pick its bucket by spelling a letter as an
 * escape: {@code POST /api/v1/auth/regist%65r} reaches the register handler but does not match
 * {@code /api/v1/auth/register}, so it falls to {@code /api/v1/auth/**} and gets three times the
 * allowance its own row grants. Worse, {@code /api/v1/%61uth/login} reaches the login handler
 * while not starting with {@code /v1/auth/}, which quietly turns the fail-closed surface back
 * into a fail-open one. {@link #pathOf} decodes once, per segment, and every one of the three
 * decisions below is made on that value.
 *
 * <h2>What is not limited</h2>
 * {@code GET /v1/status} and the actuator probes. Both are what a load balancer, the Docker
 * {@code HEALTHCHECK} and the SPA's degraded-state banner poll continuously; rate-limiting the
 * liveness probe means the container restarts itself under load, which is the failure mode the
 * probe exists to prevent.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitFilter.class);

    /** The one route that is polled by machinery rather than called by a client. */
    static final String STATUS_PATH = "/v1/status";

    /** Prefix of the routes that refuse rather than pass when the limiter cannot decide. */
    static final String FAIL_CLOSED_PREFIX = "/v1/auth/";

    private final RateLimitService limiter;
    private final RateLimitProperties properties;
    private final SecurityErrorWriter errorWriter;

    public RateLimitFilter(RateLimitService limiter, RateLimitProperties properties,
                           SecurityErrorWriter errorWriter) {
        this.limiter = limiter;
        this.properties = properties;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!properties.enabled() || isExempt(request)) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitDecision decision;
        try {
            decision = limiter.consume(identityOf(request), pathOf(request));
        } catch (RuntimeException e) {
            if (failsClosed(request)) {
                LOG.error("The rate limiter could not decide about {} {} — refusing, because the "
                        + "sign-in surface must not be reachable by breaking the limiter (§5.8)",
                    request.getMethod(), RequestPath.describe(request), e);
                refuse(request, response, HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable",
                    "The server could not check its request limits. Try again shortly.");
                return;
            }
            // swallow-ok: rethrown as a 5xx for the sign-in surface just above; here the request
            // proceeds on purpose. A limiter fault must not lock a household out of its own
            // ledger, and no credential is guessable behind these routes.
            LOG.error("The rate limiter could not decide about {} {} — allowing, because this is "
                    + "not the sign-in surface", request.getMethod(),
                RequestPath.describe(request), e);
            chain.doFilter(request, response);
            return;
        }

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        LOG.warn("Rate limit exceeded on {} {} (rule {}, {}/min burst {})", request.getMethod(),
            RequestPath.describe(request), decision.rule().pattern(),
            decision.rule().requestsPerMinute(), decision.rule().burstCapacity());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()));
        refuse(request, response, HttpStatus.TOO_MANY_REQUESTS, "rate-limited",
            "Too many requests. Try again in " + decision.retryAfterSeconds() + " seconds.");
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response,
                        HttpStatus status, String slug, String detail) throws IOException {
        if (response.getHeader(HttpHeaders.RETRY_AFTER) == null) {
            response.setHeader(HttpHeaders.RETRY_AFTER,
                String.valueOf(properties.retryAfterSeconds()));
        }
        errorWriter.write(request, response, status, slug, detail);
    }

    /**
     * Who the bucket belongs to: the signed-in account, or the address it came from.
     *
     * <p>The user id rather than the address for an authenticated caller, so that a household
     * behind one NAT does not share a ceiling, and so that a signed-in abuser cannot escape their
     * own bucket by changing address.
     *
     * <p><strong>Except on the anonymous sign-in routes</strong> ({@link SecurityConfig#PUBLIC_POST}),
     * which are metered by address whoever is signed in (B3-06). Keyed on the token, a throwaway
     * account's session bought a bucket of its own on the brute-force surface, apart from the one
     * its address had already spent.
     */
    private static String identityOf(HttpServletRequest request) {
        if (HttpMethod.POST.matches(request.getMethod())
            && SecurityConfig.PUBLIC_POST.contains(withoutContextPath(request))) {
            return ClientIpResolver.resolve(request);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
            && authentication.getPrincipal() instanceof TroveUserDetails principal) {
            return principal.getUserId().toString();
        }
        return ClientIpResolver.resolve(request);
    }

    /** {@code true} for the routes that are polled by machinery rather than called by a client. */
    static boolean isExempt(HttpServletRequest request) {
        String path = withoutContextPath(request);
        return STATUS_PATH.equals(path) || path.startsWith("/actuator");
    }

    /**
     * The request path as Spring matched it: decoded, one segment at a time, with any matrix
     * parameters dropped — and with the servlet context path still on the front, because that is
     * how {@code rate_limit_config} patterns are written ({@code /api/v1/...}), which is what an
     * operator sees in a log line and edits in the table.
     *
     * <p>Decoding is per segment and a segment whose decoded form would introduce a {@code /} is
     * left as it arrived: {@code %2F} is a character inside one segment for the dispatcher, and
     * turning it into a separator here would measure the request against a pattern the router
     * never considered. A malformed escape is left alone too — Tomcat refuses those with a 400
     * before this filter runs, and guessing at one would be inventing a path nobody sent.
     */
    static String pathOf(HttpServletRequest request) {
        String decoded = decode(request.getRequestURI());
        return decoded.isEmpty() ? "/" : decoded;
    }

    private static boolean failsClosed(HttpServletRequest request) {
        return withoutContextPath(request).startsWith(FAIL_CLOSED_PREFIX);
    }

    /**
     * The path as the routes are written, i.e. {@code /v1/auth/login} rather than
     * {@code /api/v1/auth/login}. The exemption and fail-closed lists are spelled the way §5.1
     * spells them; the rule patterns in V12 are spelled the other way because that is what an
     * operator edits.
     */
    static String withoutContextPath(HttpServletRequest request) {
        String path = pathOf(request);
        String context = decode(request.getContextPath());
        if (!context.isEmpty() && path.startsWith(context)) {
            String rest = path.substring(context.length());
            return rest.isEmpty() ? "/" : rest;
        }
        return path;
    }

    /**
     * Percent-decodes a path one segment at a time, dropping matrix parameters.
     *
     * <p>Package-private so that the evasions it closes are asserted directly rather than only
     * through a running server.
     */
    static String decode(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "";
        }
        StringBuilder decoded = new StringBuilder(rawPath.length());
        int start = 0;
        while (true) {
            int slash = rawPath.indexOf('/', start);
            int end = slash < 0 ? rawPath.length() : slash;
            decoded.append(decodeSegment(rawPath.substring(start, end)));
            if (slash < 0) {
                return decoded.toString();
            }
            decoded.append('/');
            start = slash + 1;
        }
    }

    private static String decodeSegment(String segment) {
        String value = segment;
        int semicolon = value.indexOf(';');
        if (semicolon >= 0) {
            value = value.substring(0, semicolon);
        }
        if (value.indexOf('%') < 0) {
            return value;
        }
        String plain;
        try {
            plain = UriUtils.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // swallow-ok: a malformed escape. Tomcat answers 400 for these before the chain runs,
            // so this is unreachable in the server and is here so that the method is total; the
            // raw spelling is kept rather than a guess at what was meant.
            LOG.debug("Undecodable path segment kept as sent: {}", value, e);
            return value;
        }
        // An encoded slash is a character inside ONE segment as far as the dispatcher is
        // concerned; letting it become a separator here would match a pattern the router never
        // considered. (Tomcat rejects these by default; the guard does not depend on that.)
        return plain.indexOf('/') >= 0 ? value : plain;
    }
}
