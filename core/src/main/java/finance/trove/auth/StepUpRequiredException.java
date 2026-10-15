package finance.trove.auth;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * A sensitive action was attempted without, or with a failed, re-authentication (§1.5, gap P2-6).
 *
 * <p>Holding a session is not the same as knowing the password. Every action that changes how an
 * account is secured — setting up or removing the second factor, changing the password, and from
 * later collapse steps adding a bank connection, exporting everything, deleting the account —
 * demands the password again, and the second factor too where one is enrolled. The case this
 * defends against is mundane and common: a borrowed, unlocked laptop.
 *
 * <p>403 rather than 401: the caller <em>is</em> authenticated, so answering 401 would tell every
 * client interceptor to go and refresh a session that is perfectly valid, and then retry into the
 * same refusal. The web client's interceptor refreshes on 401 and only on 401
 * ({@code apps/web/src/api/client.ts:255}), so this distinction is what keeps a wrong password on
 * a settings form from looking like an expired session.
 */
public class StepUpRequiredException extends TroveException {

    private static final long serialVersionUID = 1L;

    /** The slug named by the handoff contract for this state. */
    public static final String SLUG = "step-up-required";

    public StepUpRequiredException(String message) {
        super(message, HttpStatus.FORBIDDEN, SLUG);
    }

    /** The password offered alongside a sensitive action did not match. */
    public static StepUpRequiredException wrongPassword() {
        return new StepUpRequiredException(
            "That password is not correct. This action needs your current password.");
    }

    /** The account has a second factor and the code offered with it was not accepted. */
    public static StepUpRequiredException wrongCode() {
        return new StepUpRequiredException(
            "That authentication code was not accepted. This action needs a current code.");
    }
}
