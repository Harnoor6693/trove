package finance.trove.auth.local;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.auth.lockout.*} — the account-side brute-force control of gap P2-5.
 *
 * <p>Defaults are P2-5's: five failures per account in fifteen minutes trips a soft lock, which
 * then grows exponentially with each further failure so that a script gets slower and slower
 * while a human who mistyped twice barely notices. After {@code hardThreshold} failures the lock
 * becomes long enough to be useless to an attacker, and an admin unlock (or a password reset) is
 * the intended way out — which is why {@code POST /v1/admin/users/{id}/unlock} exists.
 *
 * <p>The per-IP bucket that complements this lives in collapse step 4's {@code RateLimitFilter};
 * both read {@code login_attempt}, which is why the window lives here rather than in either.
 *
 * @param threshold      failures inside {@code window} before the account soft-locks
 * @param window         the sliding window failures are counted in
 * @param baseBackoff    first lock duration; doubles per additional failure
 * @param maxBackoff     ceiling for the doubling, before the hard lock takes over
 * @param hardThreshold  failures inside {@code window} after which {@code hardLock} applies
 * @param hardLock       the long lock an admin is expected to clear
 * @param ipThreshold    failed logins from ONE address, across every account, inside
 *                       {@code ipWindow} before that address is refused outright with 429. This
 *                       is the control that makes credential stuffing — one guess each against a
 *                       thousand addresses — expensive, which per-account locking alone does not:
 *                       an attacker who never guesses twice at the same account never trips it
 * @param ipWindow       the sliding window the per-address count is taken over
 */
@ConfigurationProperties(prefix = "trove.auth.lockout")
public record LockoutProperties(
    int threshold,
    Duration window,
    Duration baseBackoff,
    Duration maxBackoff,
    int hardThreshold,
    Duration hardLock,
    int ipThreshold,
    Duration ipWindow) {

    public LockoutProperties {
        threshold = threshold <= 0 ? 5 : threshold;
        window = window == null ? Duration.ofMinutes(15) : window;
        baseBackoff = baseBackoff == null ? Duration.ofSeconds(30) : baseBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(15) : maxBackoff;
        hardThreshold = hardThreshold <= 0 ? 10 : hardThreshold;
        hardLock = hardLock == null ? Duration.ofHours(24) : hardLock;
        ipThreshold = ipThreshold <= 0 ? 20 : ipThreshold;
        ipWindow = ipWindow == null ? Duration.ofMinutes(10) : ipWindow;

        if (hardThreshold < threshold) {
            throw new IllegalStateException(
                "trove.auth.lockout.hard-threshold (" + hardThreshold
                    + ") must not be below threshold (" + threshold + ")");
        }
    }
}
