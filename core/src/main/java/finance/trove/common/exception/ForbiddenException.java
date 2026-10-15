package finance.trove.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals that an authenticated caller may not perform this operation — an admin-only route, or
 * a step-up factor that has not been satisfied.
 *
 * <p>Always maps to HTTP {@code FORBIDDEN} with the {@code forbidden} type slug.
 */
public class ForbiddenException extends TroveException {

    private static final long serialVersionUID = 1L;

    public ForbiddenException(String message) {
        super(message, HttpStatus.FORBIDDEN, "forbidden");
    }

    public ForbiddenException(String message, Throwable cause) {
        super(message, HttpStatus.FORBIDDEN, "forbidden", cause);
    }
}
