package finance.trove.common.exception;

import finance.trove.common.dto.ErrorResponse;
import java.util.List;

/**
 * Implemented by a {@link TroveException} that can name the input it objects to.
 *
 * <p>The point is a form, not a taxonomy. Bean-validation failures already arrive with a field
 * name attached and {@code GlobalExceptionHandler} passes it through, so the register form can
 * render "must be at least 12 characters" under the password box. Semantic refusals that happen
 * further in — this address is already taken, this password is on the deny-list — arrive as
 * exceptions with no field, and the same form has to fall back to a banner. That difference is
 * invisible to the server and glaring to whoever is filling the form in.
 *
 * <p>The web client reads {@code error.fieldErrors} as a list of {@code {field, message}} objects
 * ({@code apps/web/src/api/types.ts:44-47}) and keys its inline messages off {@code field}, so
 * the names used here have to be the request-body property names: {@code email}, {@code password},
 * {@code displayName}.
 */
public interface FieldAwareException {

    /** Never null; empty means "no field in particular". */
    List<ErrorResponse.FieldError> getFieldErrors();

    /** Convenience for the common single-field case. */
    static List<ErrorResponse.FieldError> one(String field, String message) {
        return List.of(new ErrorResponse.FieldError(field, message));
    }
}
