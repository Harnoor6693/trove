package finance.trove.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Root of Trove's typed exception hierarchy: a message, the HTTP status the caller should see,
 * and the {@code type} slug that identifies the failure in the error body.
 *
 * <p>{@link GlobalExceptionHandler} reads {@link #getStatus()} and {@link #getSlug()} instead of
 * keeping a mapping table, so a new domain exception needs no handler edit. The slug — not the
 * status — is what a client keys off: several failures share a 409.
 */
public class TroveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final HttpStatus status;
    private final String slug;

    public TroveException(String message, HttpStatus status) {
        this(message, status, defaultSlug(status));
    }

    public TroveException(String message, HttpStatus status, String slug) {
        super(message);
        this.status = status;
        this.slug = slug;
    }

    public TroveException(String message, HttpStatus status, String slug, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.slug = slug;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Trailing path segment of {@code ErrorResponse.type}, e.g. {@code not-found}. */
    public String getSlug() {
        return slug;
    }

    private static String defaultSlug(HttpStatus status) {
        return status.getReasonPhrase().toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
    }
}
