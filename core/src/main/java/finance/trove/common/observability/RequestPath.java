package finance.trove.common.observability;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Turns a request into something safe to write down.
 *
 * <p><strong>A URI is not a safe log field on this application.</strong> Two routes carry a live
 * credential in the path — {@code GET /v1/auth/invites/{token}}, which is anonymous by design so
 * that somebody handed a link can see what they are joining, and the reset link the admin pastes
 * — and one of them is hit by every recipient of every invitation. Writing
 * {@code request.getRequestURI()} verbatim put a still-redeemable 43-character invite token into
 * stdout at INFO on every one of those calls, where {@code docker logs} and any shipper keep it
 * for the full 72-hour TTL. That defeats the property the encrypted-at-rest invite column exists
 * to preserve ({@code V9_2}: "a database dump on its own still yields no working invitation") —
 * the log is a second copy, in plaintext, of the thing the column was encrypted to protect.
 *
 * <p>{@link #describe} answers with the <em>route template</em> ({@code /v1/auth/invites/{token}}),
 * which is what §4.2's audit rule already requires of {@code /v1/audit} ("route template only,
 * never raw URI", gap P2-17) and what makes log lines groupable besides. The template is put on
 * the request by {@code RequestMappingHandlerMapping} when it matches a handler, so it is
 * available to anything that runs after the dispatch — including a filter's {@code finally}
 * block, which is where the completion line is written.
 *
 * <p>When there is no template the request never reached a handler at all: the security chain
 * refused it, or nothing matched. The fallback replaces the last path segment with {@code *}.
 * That is deliberately blunt rather than clever — a heuristic that tried to recognise "this
 * segment looks like a secret" would eventually meet a secret it did not recognise, and the cost
 * of the blunt rule is one segment of detail on requests that were refused anyway.
 */
public final class RequestPath {

    private RequestPath() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The route template for {@code request}, prefixed with the servlet context path, or a
     * last-segment-redacted form of the URI when no handler was matched.
     *
     * @return never null for a non-null request; {@code "-"} when there is no request at all
     */
    public static String describe(HttpServletRequest request) {
        if (request == null) {
            return "-";
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String template && !template.isBlank()) {
            String context = request.getContextPath();
            return context == null || context.isEmpty() ? template : context + template;
        }
        return redactLastSegment(request.getRequestURI());
    }

    /**
     * {@code /api/v1/auth/invites/abc} becomes {@code /api/v1/auth/invites/*}.
     *
     * <p>A path with no segment to redact — {@code /}, {@code ""} or a trailing slash — is
     * returned unchanged: there is nothing there that could be a token.
     */
    static String redactLastSegment(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "-";
        }
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == uri.length() - 1) {
            return uri;
        }
        return uri.substring(0, lastSlash + 1) + "*";
    }
}
