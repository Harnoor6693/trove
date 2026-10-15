package finance.trove.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Signals a collision with current state: a duplicate unique key, a sync already claimed on the
 * account, or a stale optimistic-lock version.
 *
 * <p>Always maps to HTTP {@code CONFLICT} with the {@code conflict} type slug.
 */
public class ConflictException extends TroveException {

    private static final long serialVersionUID = 1L;

    public ConflictException(String message) {
        super(message, HttpStatus.CONFLICT, "conflict");
    }

    public ConflictException(String message, Throwable cause) {
        super(message, HttpStatus.CONFLICT, "conflict", cause);
    }
}
