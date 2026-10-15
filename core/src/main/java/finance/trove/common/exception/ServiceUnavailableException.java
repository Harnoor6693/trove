package finance.trove.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals that a required external dependency is transiently unreachable — the caller should
 * retry rather than change the request.
 *
 * <p>Always maps to HTTP {@code SERVICE_UNAVAILABLE} with the {@code service-unavailable} type slug.
 */
public class ServiceUnavailableException extends TroveException {

    private static final long serialVersionUID = 1L;

    public ServiceUnavailableException(String message) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable");
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(message, HttpStatus.SERVICE_UNAVAILABLE, "service-unavailable", cause);
    }
}
