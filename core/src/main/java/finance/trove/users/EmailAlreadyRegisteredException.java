package finance.trove.users;

import finance.trove.common.dto.ErrorResponse;
import finance.trove.common.exception.FieldAwareException;
import finance.trove.common.exception.TroveException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Registration was attempted with an address that already has an account.
 *
 * <p>400 with a field error rather than 409, because the two clients dispatch on status and
 * neither has a 409 branch on this form: the web register form reads {@code error.fieldErrors}
 * and renders each one under its input ({@code apps/web/src/pages/Register/RegisterForm.tsx:75}),
 * while a 409 falls through its {@code codeFromError} switch to "Trove is having trouble on its
 * end" — blaming the server for the user having an account already.
 *
 * <p>This does disclose that an address is registered, and that is a considered trade rather than
 * an oversight. Registration here is invite-gated by default: the caller already holds a token an
 * admin minted for this household, so the only fact leaked is one their admin could tell them.
 * On an install that has deliberately opened self-serve signup the leak is the ordinary
 * signup-enumeration one that every "this email is taken" form has, and the alternative — a
 * silent success that creates no account — is worse: it produces a user who believes they have
 * registered and cannot log in.
 */
public class EmailAlreadyRegisteredException extends TroveException
    implements FieldAwareException {

    private static final long serialVersionUID = 1L;

    public EmailAlreadyRegisteredException() {
        super("An account with this email address already exists.", HttpStatus.BAD_REQUEST,
            "email-taken");
    }

    @Override
    public List<ErrorResponse.FieldError> getFieldErrors() {
        return FieldAwareException.one("email", getMessage());
    }
}
