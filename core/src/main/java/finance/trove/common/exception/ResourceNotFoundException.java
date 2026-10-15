package finance.trove.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals that a requested resource does not exist — or exists in another household and must
 * therefore be indistinguishable from missing (§9 risk 2: a cross-household read answers 404,
 * never 403, so the 404 does not confirm the row exists).
 *
 * <p>Always maps to HTTP {@code NOT_FOUND} with the {@code not-found} type slug.
 */
public class ResourceNotFoundException extends TroveException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, "not-found");
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, HttpStatus.NOT_FOUND, "not-found", cause);
    }

    /** Composes the standard message, e.g. {@code "Budget not found: 3fa85f64-…"}. */
    public static ResourceNotFoundException of(String resourceType, Object id) {
        return new ResourceNotFoundException(resourceType + " not found: " + id);
    }
}
