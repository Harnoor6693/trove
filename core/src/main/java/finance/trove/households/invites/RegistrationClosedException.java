package finance.trove.households.invites;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * Somebody tried to register without an invite on an install that does not accept self-serve
 * signup (gap P2-7). Registration is closed by default.
 *
 * <p>403 rather than 400 or 404: the request was well-formed and the resource exists, the policy
 * simply refuses it. That is also what the login page needs — it is the one error slug the web
 * client compares by name ({@code apps/web/src/pages/Login/errorCopy.ts:67}), and it only reaches
 * that comparison on a 403.
 */
public class RegistrationClosedException extends TroveException {

    private static final long serialVersionUID = 1L;

    /** The one slug the web client matches on by name; the wire form is the {@code type} URI. */
    public static final String SLUG = "registration-closed";

    public RegistrationClosedException() {
        super("This Trove is invite-only. Ask a household admin for an invitation link.",
            HttpStatus.FORBIDDEN, SLUG);
    }
}
