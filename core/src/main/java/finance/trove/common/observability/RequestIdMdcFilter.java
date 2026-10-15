package finance.trove.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes {@code requestId} (and, once auth exists, {@code userId}) in the MDC for the whole
 * request, echoes the id in {@code X-Request-Id}, and emits one status-aware completion line.
 *
 * <p>Moved from {@code libs/common} per {@code docs/recovery/06-COLLAPSE-PLAN.md} §2.11 with two
 * changes:
 * <ul>
 *   <li>The {@code @Component} and {@code @Order} annotations are <strong>gone</strong>. In the
 *       source, being a {@code @Component} <em>and</em> a servlet filter is what made Boot
 *       register every filter twice in seven services (§9 risk 8). Registration now happens
 *       exactly once, explicitly, in {@code finance.trove.app.ObservabilityConfig} through a
 *       {@code FilterRegistrationBean} at {@code HIGHEST_PRECEDENCE} — ahead of the security
 *       chain, so a 401 and a servlet-container error page both carry a request id.</li>
 *   <li>The MDC {@code userId} is read from {@code Authentication.getName()} rather than casting
 *       the principal to {@code TroveUserDetails}, which does not exist until collapse step 3
 *       and whose subject is that same string.</li>
 * </ul>
 *
 * <p>An inbound {@code X-Request-Id} is honoured only if it parses as a UUID: the value is
 * echoed into logs and into the error body, so an unvalidated header would be a log-injection
 * and response-splitting surface on a public endpoint.
 *
 * <p>The MDC is cleared in a {@code finally} even when the chain throws — on a pooled container
 * thread a leaked id would be attributed to the next, unrelated request.
 */
public final class RequestIdMdcFilter extends OncePerRequestFilter {

    /** Inbound and outbound header name; matches what the SPA and mobile already send. */
    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Request attribute, readable after the MDC has been cleared (e.g. an ERROR dispatch). */
    public static final String REQUEST_ID_ATTRIBUTE = "trove.requestId";

    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_USER_ID = "userId";

    private static final Logger LOG = LoggerFactory.getLogger(RequestIdMdcFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        UUID requestId = parseOrGenerate(request.getHeader(REQUEST_ID_HEADER));
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId.toString());
        if (response.getHeader(REQUEST_ID_HEADER) == null) {
            response.setHeader(REQUEST_ID_HEADER, requestId.toString());
        }

        long startNanos = System.nanoTime();
        MDC.put(MDC_REQUEST_ID, requestId.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                populateUserId();
                logCompletion(request, response, startNanos);
            } finally {
                MDC.remove(MDC_REQUEST_ID);
                MDC.remove(MDC_USER_ID);
            }
        }
    }

    /**
     * One line per request, and never the raw URI.
     *
     * <p>{@link RequestPath#describe} is what makes this line safe to keep: two paths on this
     * application carry a live credential — the anonymous invite preview
     * {@code GET /v1/auth/invites/{token}} above all — and this statement runs at INFO for every
     * 2xx. Logging {@code request.getRequestURI()} here wrote a still-redeemable invite token to
     * stdout on every preview, for the whole 72-hour life of the invitation.
     */
    private void logCompletion(HttpServletRequest request, HttpServletResponse response,
                               long startNanos) {
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
        int status = response.getStatus();
        String path = RequestPath.describe(request);
        if (status >= 500) {
            LOG.error("{} {} completed status={} durationMs={}", request.getMethod(),
                path, status, durationMs);
        } else if (status >= 400) {
            LOG.warn("{} {} completed status={} durationMs={}", request.getMethod(),
                path, status, durationMs);
        } else {
            LOG.info("{} {} completed status={} durationMs={}", request.getMethod(),
                path, status, durationMs);
        }
    }

    /**
     * Accepts a caller-supplied id only when it is a well-formed UUID; anything else — including
     * a header carrying a newline — is replaced with a fresh one.
     */
    static UUID parseOrGenerate(String header) {
        if (header == null || header.length() != 36) {
            return UUID.randomUUID();
        }
        try {
            UUID parsed = UUID.fromString(header);
            return parsed.toString().equals(header.toLowerCase(java.util.Locale.ROOT))
                ? parsed
                : UUID.randomUUID();
        } catch (IllegalArgumentException ex) {
            return UUID.randomUUID();
        }
    }

    /**
     * Runs after the chain returns, so the JWT filter (collapse step 3) has had its chance to
     * populate the security context before the completion line is written.
     */
    private void populateUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
            && !"anonymousUser".equals(auth.getName())) {
            MDC.put(MDC_USER_ID, auth.getName());
        }
    }
}
