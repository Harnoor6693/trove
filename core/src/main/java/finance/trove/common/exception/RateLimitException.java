package finance.trove.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals that a caller exceeded an enforced request rate.
 *
 * <p>Always maps to HTTP {@code TOO_MANY_REQUESTS} with the {@code rate-limited} type slug.
 */
public class RateLimitException extends TroveException {

    private static final long serialVersionUID = 1L;

    public RateLimitException(String message) {
        super(message, HttpStatus.TOO_MANY_REQUESTS, "rate-limited");
    }

    public RateLimitException(String message, Throwable cause) {
        super(message, HttpStatus.TOO_MANY_REQUESTS, "rate-limited", cause);
    }
}
