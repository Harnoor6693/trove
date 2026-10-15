package finance.trove.auth.totp;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/** A TOTP code or recovery code was rejected, or an enrolment step was taken out of order. */
public class TotpException extends TroveException {

    private static final long serialVersionUID = 1L;

    private TotpException(String message, HttpStatus status, String slug) {
        super(message, status, slug);
    }

    /** A wrong or already-used code. 401, and deliberately not specific about which. */
    public static TotpException invalidCode() {
        return new TotpException("That code is not valid.", HttpStatus.UNAUTHORIZED,
            "totp-invalid");
    }

    /** Confirm or disable called on a user who has not started enrolment. */
    public static TotpException notEnrolled() {
        return new TotpException("Two-factor authentication is not set up on this account.",
            HttpStatus.CONFLICT, "totp-not-enrolled");
    }

    /** Enrolment started while TOTP is already enabled: disable it first, deliberately. */
    public static TotpException alreadyEnabled() {
        return new TotpException(
            "Two-factor authentication is already enabled. Disable it before enrolling again.",
            HttpStatus.CONFLICT, "totp-already-enabled");
    }
}
