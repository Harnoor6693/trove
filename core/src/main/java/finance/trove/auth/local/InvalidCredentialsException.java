package finance.trove.auth.local;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * The single failure a login can report ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, gap
 * P2-4).
 *
 * <p>Its whole design is that there is only one of it. Unknown address, wrong password, locked
 * account, deactivated account and a user with no local credential at all (OIDC-only) must all
 * produce this exact status, slug and message. Anything finer turns the login form into an
 * account-enumeration oracle — "which of these addresses has an account on this family's finance
 * server?" — and a lock-specific message additionally tells an attacker their guessing is
 * working.
 *
 * <p>The <em>slug</em> is what the web client keys its message off, and it is
 * {@code credentials-invalid} in every one of those cases for the same reason.
 */
public class InvalidCredentialsException extends TroveException {

    private static final long serialVersionUID = 1L;

    public static final String DETAIL = "Email or password is incorrect.";

    public InvalidCredentialsException() {
        super(DETAIL, HttpStatus.UNAUTHORIZED, "credentials-invalid");
    }
}
