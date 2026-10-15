package finance.trove.auth.local;

import finance.trove.common.dto.ErrorResponse;
import finance.trove.common.exception.FieldAwareException;
import finance.trove.common.exception.TroveException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A password was refused by the policy of readiness gap P2-4.
 *
 * <p>400, not 422: collapse step 2 settled that every validation failure in the monolith is a 400
 * so the clients do not have to branch on two "you sent something wrong" statuses. The slug
 * {@code password-policy} is what the web client keys its message off.
 *
 * <p>The message is deliberately specific ("at least 12 characters", "this password appears on a
 * list of commonly used passwords") because the caller is the account's own owner choosing their
 * own password — there is no enumeration risk here, and a vague refusal just produces three more
 * attempts.
 */
public class PasswordPolicyException extends TroveException implements FieldAwareException {

    private static final long serialVersionUID = 1L;

    /** Request-body property name, so the message renders under the password box, not as a banner. */
    private static final String FIELD = "password";

    public PasswordPolicyException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "password-policy");
    }

    @Override
    public List<ErrorResponse.FieldError> getFieldErrors() {
        return FieldAwareException.one(FIELD, getMessage());
    }
}
