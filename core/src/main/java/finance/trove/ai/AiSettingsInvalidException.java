package finance.trove.ai;

import finance.trove.common.dto.ErrorResponse;
import finance.trove.common.exception.FieldAwareException;
import finance.trove.common.exception.TroveException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A settings body that parsed and passed Bean Validation but breaks a rule those annotations
 * cannot express: enabling AI with no endpoint, enabling it with no model, or sending an
 * all-whitespace API key.
 *
 * <p>400 with slug {@code validation-error} — the same slug Bean Validation failures carry —
 * because from a client's point of view this is the same event, and the settings form renders it
 * the same way. It names its field so the message lands under the right input rather than as a
 * banner, which is the whole reason {@link FieldAwareException} exists.
 */
public class AiSettingsInvalidException extends TroveException implements FieldAwareException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public AiSettingsInvalidException(String field, String message) {
        super(message, HttpStatus.BAD_REQUEST, "validation-error");
        this.field = field;
    }

    @Override
    public List<ErrorResponse.FieldError> getFieldErrors() {
        return FieldAwareException.one(field, getMessage());
    }
}
