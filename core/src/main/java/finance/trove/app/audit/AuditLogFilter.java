package finance.trove.app.audit;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.net.ClientIpResolver;
import finance.trove.common.observability.RequestIdMdcFilter;
import finance.trove.common.observability.RequestPath;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Writes one {@code audit_log} row per mutating request ({@code docs/recovery/06-COLLAPSE-PLAN.md}
 * §4.2, §5.1; gap P2-17). A REWRITE of the gateway filter, not a move — three things about that
 * one were wrong.
 *
 * <h2>1. No {@code ContentCachingResponseWrapper}</h2>
 * The source wrapped every response in one and called {@code copyBodyToResponse()} in a
 * {@code finally}. That buffers the entire response body in heap for every request in order to
 * record its <em>status</em>, which {@code HttpServletResponse#getStatus()} already reports without
 * buffering anything — and it breaks streaming responses, which is the shape of the export and
 * receipt endpoints later steps add (§2.1 lists the rewrite for this reason). Nothing here wraps
 * the response.
 *
 * <h2>2. The route template, never the raw URI</h2>
 * {@link RequestPath#describe} is the whole difference between an audit table and a permanent,
 * plaintext copy of every invitation token the install has issued: {@code
 * GET /v1/auth/invites/{token}} is anonymous by design, and V9_2 encrypts that token at rest
 * precisely so a database dump yields no working invitation. Storing the URI would put the
 * cleartext back in the same database. The template is set on the request by
 * {@code RequestMappingHandlerMapping} during dispatch, so it is available here in the
 * {@code finally}.
 *
 * <h2>3. The address is not parsed by hand</h2>
 * {@link ClientIpResolver}, i.e. the socket after the trusted-proxy valve. The source took the
 * first {@code X-Forwarded-For} value, which any caller can set, so the address recorded against a
 * request was whatever that request asked for it to be — in an audit table, which is the one place
 * that matters most (gap P2-5).
 *
 * <h2>What is recorded, and what is not</h2>
 * Mutating methods only: {@code POST}, {@code PUT}, {@code PATCH}, {@code DELETE}. Reads would
 * dominate the table by an order of magnitude and §4.2's question is "what changed, and who
 * changed it". Nothing about the body, the headers, the query string or any token is captured —
 * there is no code path here that could.
 *
 * <p><strong>A refusal by an earlier filter is not audited here.</strong> §5.1 puts this filter
 * last, so a request the CSRF filter or the rate limiter turned away never reaches it. Those two
 * refusals are logged, with the route template and the request id, by the filter that made them
 * and by {@code RequestIdMdcFilter}'s completion line. Moving this filter outward to catch them
 * would cost it the principal, which is the column an audit table exists for.
 */
public class AuditLogFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(AuditLogFilter.class);

    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AuditLogWriter writer;

    public AuditLogFilter(AuditLogWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!MUTATING.contains(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            record(request, response, startNanos);
        }
    }

    /**
     * Captures the row and hands it to the executor.
     *
     * <p>Everything that can throw is inside the try: this runs in a {@code finally} on the
     * request thread, and an exception raised here would replace whatever the request was really
     * doing — including, at the worst possible moment, an exception the handler was already
     * propagating.
     */
    private void record(HttpServletRequest request, HttpServletResponse response,
                        long startNanos) {
        try {
            TroveUserDetails principal = principalOf();
            AuditRecord captured = new AuditRecord(
                request.getMethod(),
                RequestPath.describe(request),
                response.getStatus(),
                principal == null ? null : principal.getUserId(),
                principal == null ? null : principal.getHouseholdId(),
                ClientIpResolver.resolve(request),
                requestIdOf(request),
                (System.nanoTime() - startNanos) / 1_000_000L);
            writer.write(captured);
        } catch (RuntimeException e) {
            // swallow-ok: the response is already written and on its way to the client. An audit
            // failure must never become the answer the caller receives.
            LOG.error("Could not capture an audit row for {} {}", request.getMethod(),
                RequestPath.describe(request), e);
        }
    }

    private static TroveUserDetails principalOf() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
            && authentication.getPrincipal() instanceof TroveUserDetails principal
            ? principal
            : null;
    }

    /**
     * The id {@code RequestIdMdcFilter} put on the request. That filter has the highest precedence
     * and runs for every dispatch type, so the attribute is always there; the fallback exists so
     * that a unit test exercising this filter alone still produces a writable row rather than a
     * null in a {@code NOT NULL} column.
     */
    private static UUID requestIdOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdMdcFilter.REQUEST_ID_ATTRIBUTE);
        if (attribute != null) {
            try {
                return UUID.fromString(attribute.toString());
            } catch (IllegalArgumentException e) {
                // swallow-ok: falls through to a generated id below. The attribute is written by
                // RequestIdMdcFilter, which only ever puts a UUID there, so this is unreachable in
                // the running application and is here so that it stays unreachable rather than
                // becoming a null in a NOT NULL column if that ever changes.
                LOG.warn("Request attribute {} was not a UUID: {}",
                    RequestIdMdcFilter.REQUEST_ID_ATTRIBUTE, attribute);
            }
        }
        return UUID.randomUUID();
    }
}
