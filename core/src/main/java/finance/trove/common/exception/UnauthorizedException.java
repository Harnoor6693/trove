package finance.trove.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals missing, expired or invalid credentials.
 *
 * <p>Always maps to HTTP {@code UNAUTHORIZED} with the {@code unauthorized} type slug.
 */
public class UnauthorizedException extends TroveException {

    private static final long serialVersionUID = 1L;

    public UnauthorizedException(String message) {
        super(message, HttpStatus.UNAUTHORIZED, "unauthorized");
    }

    public UnauthorizedException(String message, Throwable cause) {
        super(message, HttpStatus.UNAUTHORIZED, "unauthorized", cause);
    }
}
