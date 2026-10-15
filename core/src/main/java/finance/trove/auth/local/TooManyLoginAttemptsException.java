package finance.trove.auth.local;

import finance.trove.common.exception.RetryAfterAware;
import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * One network address has failed too many logins across the install (gap P2-5).
 *
 * <p>This is the <em>only</em> place in the login flow that answers with something other than
 * {@link InvalidCredentialsException}, and the difference is safe: it is keyed on the caller's own
 * address, not on whether an account exists, so it reveals nothing about who banks here. It says
 * "you, specifically, have been guessing", which is information the guesser already has.
 *
 * <p>The slug is {@code account-locked} because that is the string both clients already switch on
 * for this state (it is only ever sent on the 429 path).
 *
 * <p>The check that raises this is <strong>fail-closed</strong>: if the {@code login_attempt}
 * count cannot be taken, the request is refused rather than admitted. A brute-force control that
 * fails open is not a control — the pre-collapse limiter logged "failing open" on every backing
 * store error, which made a denial of service against the store into a way to switch the limiter
 * off (gap P2-5).
 *
 * <p>It carries a {@code Retry-After} value because the spec promises one on every 429 these two
 * operations can answer, and {@code GlobalExceptionHandler} has no other way to know it: the wait
 * is the lockout window for the gate and a short fixed interval when the count itself could not
 * be taken (confirmed review finding 3).
 */
public class TooManyLoginAttemptsException extends TroveException implements RetryAfterAware {

    private static final long serialVersionUID = 1L;

    /** Slug the clients read; deliberately shared with the account-side state. */
    public static final String SLUG = "account-locked";

    private static final String MESSAGE =
        "Too many sign-in attempts from this network. Try again later.";

    private final long retryAfterSeconds;

    /**
     * @param retryAfterSeconds whole seconds to wait; clamped to at least 1, which is the
     *                          minimum the spec's {@code RetryAfter} header declares
     */
    public TooManyLoginAttemptsException(long retryAfterSeconds) {
        super(MESSAGE, HttpStatus.TOO_MANY_REQUESTS, SLUG);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public TooManyLoginAttemptsException(long retryAfterSeconds, Throwable cause) {
        super(MESSAGE, HttpStatus.TOO_MANY_REQUESTS, SLUG, cause);
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    @Override
    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
