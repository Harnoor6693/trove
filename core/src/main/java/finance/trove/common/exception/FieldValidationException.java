package finance.trove.common.exception;

import finance.trove.common.dto.ErrorResponse;
import java.util.List;

/**
 * A {@link ValidationException} that names the request-body property it refuses, so the form
 * that sent it can put the message under that input instead of in a banner.
 */
public class FieldValidationException extends ValidationException implements FieldAwareException {

    private static final long serialVersionUID = 1L;

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    @Override
    public List<ErrorResponse.FieldError> getFieldErrors() {
        return FieldAwareException.one(field, getMessage());
    }
}
