package finance.trove.common.exception;

import finance.trove.common.observability.RequestPath;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.ErrorResponse;
import finance.trove.common.json.TroveJsonModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;

/**
 * The monolith's single exception-to-response translator
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.3), a REWRITE of the {@code libs/common} handler
 * per §2.11.
 *
 * <p>What the rewrite adds over the seven handlers it replaces: {@link ResponseStatusException}
 * (its own status, so Spring's own 404/415 keep the envelope), {@link AccessDeniedException}
 * → 403 and {@link AuthenticationException} → 401 (today an admin-route 403 falls through to a
 * bare 500 — gap P2-7), {@link ConstraintViolationException} alongside
 * {@link MethodArgumentNotValidException}, {@link MaxUploadSizeExceededException} → 413 (a
 * receipt over the 20 MB multipart cap, §1.6), and {@link DataIntegrityViolationException} /
 * {@link OptimisticLockingFailureException} → 409.
 *
 * <p>Two changes of behaviour from the source handler:
 * <ul>
 *   <li>Bean-Validation failures are <strong>400</strong>, not 422. The clients treat 4xx
 *       uniformly and {@code fieldErrors} is what they actually read; keeping one status for
 *       "your request was malformed" removes a distinction neither client acts on.</li>
 *   <li>{@link #handleUnexpected} logs the cause against the request id and returns a fixed
 *       sentence. The source handler put {@code ex.getMessage()} on the wire for
 *       {@link IllegalArgumentException}, which leaks internals from any library that throws
 *       one.</li>
 * </ul>
 *
 * <p>Ordering note: Spring picks the most specific handler, so {@link TroveException} wins over
 * {@link RuntimeException} for every domain exception and this class needs no explicit order.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ErrorResponseFactory errors;

    public GlobalExceptionHandler(ErrorResponseFactory errors) {
        this.errors = errors;
    }

    /** Every domain exception: status and slug come from the exception itself. */
    @ExceptionHandler(TroveException.class)
    public ResponseEntity<ApiResponse<Void>> handleTrove(TroveException ex,
                                                         HttpServletRequest request) {
        if (ex.getStatus().is5xxServerError()) {
            LOG.error("{} at {}", ex.getClass().getSimpleName(), RequestPath.describe(request), ex);
        } else {
            LOG.warn("{} at {}: {}", ex.getClass().getSimpleName(), RequestPath.describe(request),
                ex.getMessage());
        }
        // A domain exception that knows which input it objected to renders inline on the form
        // that produced it, exactly as a bean-validation failure does. Without this branch a
        // deny-listed password and a taken address both come back as an unattributed banner
        // while a too-short password lands under the right box, which reads as a bug.
        List<ErrorResponse.FieldError> fieldErrors = ex instanceof FieldAwareException aware
            ? aware.getFieldErrors()
            : null;
        ResponseEntity<ApiResponse<Void>> response =
            respond(ex.getStatus(), ex.getSlug(), ex.getMessage(), request, fieldErrors);
        // openapi/trove-core.yaml promises Retry-After on every 429 this API answers — the
        // LoginThrottled response says so in as many words — and until this branch existed only
        // RateLimitFilter, which writes its own response, ever set it. The brute-force gate's 429
        // came out of here bare, so a client backing off by the documented header had nothing to
        // read (confirmed review finding 3).
        if (ex instanceof RetryAfterAware retryable) {
            return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(retryable.getRetryAfterSeconds()))
                .body(response.getBody());
        }
        return response;
    }

    /** {@code @Valid} on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleBodyValidation(
        MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> new ErrorResponse.FieldError(fe.getField(), message(fe.getDefaultMessage())))
            .toList();
        return respond(HttpStatus.BAD_REQUEST, "validation-error",
            "One or more fields failed validation.", request, fieldErrors);
    }

    /** {@code @Validated} on a path variable, request parameter or service method. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(
        ConstraintViolationException ex, HttpServletRequest request) {

        List<ErrorResponse.FieldError> fieldErrors = ex.getConstraintViolations().stream()
            .map(v -> new ErrorResponse.FieldError(lastNode(v), message(v.getMessage())))
            .toList();
        return respond(HttpStatus.BAD_REQUEST, "validation-error",
            "One or more fields failed validation.", request, fieldErrors);
    }

    /** A missing required query parameter is a field error too, not an opaque 400. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
        MissingServletRequestParameterException ex, HttpServletRequest request) {

        return respond(HttpStatus.BAD_REQUEST, "validation-error",
            "One or more fields failed validation.", request,
            List.of(new ErrorResponse.FieldError(ex.getParameterName(), "is required")));
    }

    /** {@code ?limit=abc} on an {@code int}, or a malformed UUID in the path. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
        MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        return respond(HttpStatus.BAD_REQUEST, "validation-error",
            "One or more fields failed validation.", request,
            List.of(new ErrorResponse.FieldError(ex.getName(), "is not a valid value")));
    }

    /**
     * Malformed JSON. The parser message is deliberately dropped: it quotes the offending bytes,
     * which for a login request means the password lands in the response body.
     *
     * <p>The one exception is an amount the money columns cannot hold (B2-02): the JSON is fine,
     * the value is not, so it is a field error. Its message names the rule, never the value.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex,
                                                               HttpServletRequest request) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof TroveJsonModule.AmountOutOfRangeException amount) {
                List<JacksonException.Reference> path = amount.getPath();
                String field = path.isEmpty() || path.getLast().getPropertyName() == null
                    ? "body" : path.getLast().getPropertyName();
                return respond(HttpStatus.BAD_REQUEST, "validation-error",
                    "One or more fields failed validation.", request,
                    List.of(new ErrorResponse.FieldError(field, amount.getOriginalMessage())));
            }
        }
        LOG.debug("Unreadable request body at {}", RequestPath.describe(request), ex);
        return respond(HttpStatus.BAD_REQUEST, "malformed-request",
            "The request body could not be parsed as JSON.", request);
    }

    /** A receipt or import file over the multipart cap of §1.6. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(
        MaxUploadSizeExceededException ex, HttpServletRequest request) {

        LOG.warn("Upload rejected at {}: exceeds the configured maximum", RequestPath.describe(request));
        return respond(HttpStatus.CONTENT_TOO_LARGE, "upload-too-large",
            "The uploaded file is larger than the maximum this server accepts.", request);
    }

    /** A unique-constraint or foreign-key violation is the caller's conflict, not a 500. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
        DataIntegrityViolationException ex, HttpServletRequest request) {

        LOG.warn("Data integrity violation at {}", RequestPath.describe(request), ex);
        return respond(HttpStatus.CONFLICT, "conflict",
            "The request conflicts with the current state of the resource.", request);
    }

    /** Two writers raced on the same row; the loser retries. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(
        OptimisticLockingFailureException ex, HttpServletRequest request) {

        LOG.warn("Optimistic lock failure at {}: {}", RequestPath.describe(request), ex.getMessage());
        return respond(HttpStatus.CONFLICT, "concurrent-modification",
            "The resource was modified by another request. Reload and try again.", request);
    }

    /**
     * Spring Security's 401 raised inside a controller or a service. The security chain's own
     * refusals never reach here — they are written by {@link TroveAuthenticationEntryPoint} —
     * but a manual {@code AuthenticationManager.authenticate} call does.
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex,
                                                                   HttpServletRequest request) {
        LOG.warn("Authentication failure at {}: {}", RequestPath.describe(request), ex.getMessage());
        return respond(HttpStatus.UNAUTHORIZED, "unauthorized",
            "Authentication is required to access this resource.", request);
    }

    /**
     * A method-security refusal ({@code @PreAuthorize}). The chain-level equivalent is
     * {@link TroveAccessDeniedHandler}; both emit this body, closing gap P2-7's bare 500.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex,
                                                                 HttpServletRequest request) {
        LOG.warn("Access denied at {}: {}", RequestPath.describe(request), ex.getMessage());
        return respond(HttpStatus.FORBIDDEN, "forbidden",
            "You do not have permission to perform this action.", request);
    }

    /** A hand-thrown {@code ResponseStatusException} keeps its own status and reason. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException ex,
                                                                   HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String detail = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        if (status.is5xxServerError()) {
            LOG.error("ResponseStatusException at {}", RequestPath.describe(request), ex);
            detail = ErrorResponseFactory.INTERNAL_DETAIL;
        }
        return respond(status, slugFor(status), detail, request);
    }

    /** No handler matched the path. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex,
                                                               HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "not-found",
            "No resource exists at " + RequestPath.describe(request) + ".", request);
    }

    /** Right path, wrong verb. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
        HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        return respond(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed",
            ex.getMethod() + " is not supported by this endpoint.", request);
    }

    /**
     * Everything else. The cause is logged with the request id and the caller gets a fixed
     * sentence — no class name, no message, no stack trace (§5.3).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex,
                                                               HttpServletRequest request) {
        String requestId = errors.requestId(request);
        LOG.error("Unhandled {} at {} (requestId={})", ex.getClass().getName(),
            RequestPath.describe(request), requestId, ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal-server-error",
            ErrorResponseFactory.INTERNAL_DETAIL, request);
    }

    private ResponseEntity<ApiResponse<Void>> respond(HttpStatus status, String slug, String detail,
                                                      HttpServletRequest request) {
        return respond(status, slug, detail, request, null);
    }

    private ResponseEntity<ApiResponse<Void>> respond(HttpStatus status, String slug, String detail,
                                                      HttpServletRequest request,
                                                      List<ErrorResponse.FieldError> fieldErrors) {
        return ResponseEntity.status(status)
            .body(ApiResponse.error(errors.build(status, slug, detail, request, fieldErrors)));
    }

    private static String slugFor(HttpStatus status) {
        return status.getReasonPhrase().toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
    }

    private static String message(String raw) {
        return raw == null || raw.isBlank() ? "is invalid" : raw;
    }

    private static String lastNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int dot = path.lastIndexOf('.');
        return dot >= 0 && dot < path.length() - 1 ? path.substring(dot + 1) : path;
    }
}
