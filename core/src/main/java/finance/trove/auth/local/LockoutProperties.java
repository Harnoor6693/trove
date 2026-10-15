package finance.trove.auth.local;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.auth.lockout.*} — the brute-force controls of gap P2-5, shaped by OWASP ASVS
 * v4 §2.2.1: effective against guessing <em>and</em> against an attacker locking the owner out,
 * with no more than 100 failed attempts an hour possible on one account.
 *
 * <p>Three layers, from the narrowest:
 * <ol>
 *   <li><strong>Per account, per address.</strong> {@code threshold} failures for one account
 *       from one address with no quiet {@code window} between them throttle that address for
 *       that account, for {@code baseBackoff}, doubling with each further failure up to
 *       {@code maxBackoff}; the run ends only after a quiet {@code window} once the throttle is
 *       over. A human who mistyped twice barely notices; a script gets about twenty guesses an
 *       hour from one address. Other addresses are untouched, so an attacker's failures cannot
 *       lock the owner out.</li>
 *   <li><strong>Per account, every address.</strong> {@code accountThreshold} failures inside
 *       {@code accountWindow}, from anywhere, throttle the account for every address that has not
 *       signed in to it before. This is the ASVS ceiling a botnet with one address per guess would
 *       otherwise walk around; the exemption is ASVS's own "risk-based restriction", and it is
 *       what keeps the owner signing in from home while the account is under attack. The second
 *       factor exempts no address: whoever reaches it already has the password.</li>
 *   <li><strong>Per address, every account.</strong> {@code ipThreshold} failures from one
 *       address inside {@code ipWindow} answer 429 before any credential is read.</li>
 * </ol>
 * There is no long lock. The 24-hour lock this replaced was exactly what an attacker wanted: ten
 * requests signed a member out of their own finance server for a day.
 *
 * @param threshold        failures in one run from one address before that address is throttled
 *                         for that account
 * @param window           the quiet period that ends a run
 * @param baseBackoff      first throttle duration; doubles per additional failure
 * @param maxBackoff       ceiling for the doubling
 * @param accountThreshold failures for one account from every address inside
 *                         {@code accountWindow} before unrecognised addresses are throttled
 * @param accountWindow    the sliding window the per-account count is taken over
 * @param ipThreshold      failed logins from ONE address, across every account, inside
 *                         {@code ipWindow} before that address is refused outright with 429. This
 *                         is the control that makes credential stuffing — one guess each against
 *                         a thousand addresses — expensive, which per-account counting alone does
 *                         not: an attacker who never guesses twice at one account never trips it
 * @param ipWindow         the sliding window the per-address count is taken over
 */
@ConfigurationProperties(prefix = "trove.auth.lockout")
public record LockoutProperties(
    int threshold,
    Duration window,
    Duration baseBackoff,
    Duration maxBackoff,
    int accountThreshold,
    Duration accountWindow,
    int ipThreshold,
    Duration ipWindow) {

    public LockoutProperties {
        threshold = threshold <= 0 ? 5 : threshold;
        window = window == null ? Duration.ofMinutes(15) : window;
        baseBackoff = baseBackoff == null ? Duration.ofSeconds(30) : baseBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(15) : maxBackoff;
        accountThreshold = accountThreshold <= 0 ? 100 : accountThreshold;
        accountWindow = accountWindow == null ? Duration.ofHours(1) : accountWindow;
        ipThreshold = ipThreshold <= 0 ? 20 : ipThreshold;
        ipWindow = ipWindow == null ? Duration.ofMinutes(10) : ipWindow;

        if (accountThreshold < threshold) {
            throw new IllegalStateException(
                "trove.auth.lockout.account-threshold (" + accountThreshold
                    + ") must not be below threshold (" + threshold + ")");
        }
    }
}
