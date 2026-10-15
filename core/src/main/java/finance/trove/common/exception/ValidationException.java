package finance.trove.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals that syntactically valid input broke a business rule Bean Validation cannot express.
 *
 * <p>Always maps to HTTP {@code BAD_REQUEST} with the {@code validation-error} type slug.
 */
public class ValidationException extends TroveException {

    private static final long serialVersionUID = 1L;

    public ValidationException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "validation-error");
    }

    public ValidationException(String message, Throwable cause) {
        super(message, HttpStatus.BAD_REQUEST, "validation-error", cause);
    }
}
