package finance.trove.common.exception;

import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.ErrorResponse;
import finance.trove.common.observability.RequestIdMdcFilter;
import finance.trove.common.observability.RequestPath;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Builds the one error body ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.3) so that
 * {@link GlobalExceptionHandler}, {@link TroveAuthenticationEntryPoint} and
 * {@link TroveAccessDeniedHandler} cannot drift apart — a 401 written by the security chain has
 * to be byte-for-byte the same shape as a 404 written by a controller advice, because the
 * clients parse both with one function.
 *
 * <p>{@code instance} is the matched <em>route template</em>, not the URI
 * ({@link RequestPath}). Two paths on this application carry a live credential in the path, and
 * an error body is the one part of a response that clients routinely copy into a console, a bug
 * report or an error tracker; the template says which endpoint refused without saying what was
 * presented to it.
 *
 * <p>The {@code requestId} comes from the MDC that {@link RequestIdMdcFilter} populated, falling
 * back to the request attribute the same filter sets. Both are present for every request that
 * reached the servlet container, including the ones the security chain rejects before any
 * controller runs, because that filter has the highest precedence.
 */
@Component
public class ErrorResponseFactory {

    /**
     * The body of a 500. Fixed text on purpose: the cause is written to the log against the same
     * {@code requestId}, never to the response (§5.3 — no stack traces on the wire).
     */
    public static final String INTERNAL_DETAIL =
        "An unexpected error occurred. Quote the requestId when reporting this.";

    public ErrorResponse build(HttpStatus status, String slug, String detail,
                               HttpServletRequest request) {
        return build(status, slug, detail, request, null);
    }

    public ErrorResponse build(HttpStatus status, String slug, String detail,
                               HttpServletRequest request,
                               List<ErrorResponse.FieldError> fieldErrors) {
        return ErrorResponse.builder(status.value())
            .type(slug)
            .title(status.getReasonPhrase())
            .detail(detail)
            .instance(request == null ? null : RequestPath.describe(request))
            .requestId(requestId(request))
            .fieldErrors(fieldErrors)
            .build();
    }

    /** Convenience for the call sites that hand the envelope straight to a writer. */
    public ApiResponse<Void> envelope(HttpStatus status, String slug, String detail,
                                      HttpServletRequest request) {
        return ApiResponse.error(build(status, slug, detail, request));
    }

    /**
     * The correlation id for this request: MDC first (set for the whole request), then the
     * request attribute (still set when the MDC has already been cleared, as it is when the
     * container dispatches to {@code /error} on a new thread).
     */
    public String requestId(HttpServletRequest request) {
        String fromMdc = MDC.get(RequestIdMdcFilter.MDC_REQUEST_ID);
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        if (request != null) {
            Object attribute = request.getAttribute(RequestIdMdcFilter.REQUEST_ID_ATTRIBUTE);
            if (attribute != null) {
                return attribute.toString();
            }
        }
        return null;
    }
}
