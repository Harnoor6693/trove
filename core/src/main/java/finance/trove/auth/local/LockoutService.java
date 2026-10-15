package finance.trove.auth.local;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a stream of failed logins into throttles (gap P2-5; OWASP ASVS v4 §2.2.1).
 *
 * <p>The policy is {@link LockoutProperties}': a throttle per account <em>and address</em> with a
 * doubling backoff, a per-account ceiling that only addresses the owner has signed in from are
 * exempt from, and the per-address gate below. There is no long lock, and no lock that one
 * address's failures place on every other address: either lets anybody who knows a member's
 * email sign them out of their own finance server.
 *
 * <p>Every decision is taken from {@code login_attempt}, which records the address of each
 * attempt; the credential row has nowhere to keep a lock per address. Its {@code failed_attempts}
 * and {@code locked_until} (schema V4) hold the most recent throttle placed on the account and
 * the count behind it — a record for whoever investigates, which no gate reads.
 *
 * <p><strong>An attempt made while throttled is not recorded.</strong> It is refused without its
 * password being considered, so it is not a guess; counting it would only extend the throttle of
 * an owner who retries too soon, and would let an attacker hold an account above the ceiling at
 * no cost.
 *
 * <p>Nothing here is ever visible to the caller. {@code LocalAccountService} throws the same
 * {@link InvalidCredentialsException} whether the address is throttled or the password was simply
 * wrong (§1.5), and every rule applies to an email with no account exactly as to one with one.
 */
@Service
public class LockoutService {

    private static final Logger LOG = LoggerFactory.getLogger(LockoutService.class);

    /**
     * What a caller refused because the <em>count</em> failed is told to wait. A database that
     * cannot answer a single indexed count is having a moment, not an outage measured in windows;
     * a minute is long enough not to hammer it and short enough that a legitimate sign-in is not
     * locked out for ten.
     */
    private static final Duration COUNT_FAILURE_RETRY = Duration.ofMinutes(1);

    /**
     * What a caller with no resolvable address is told to wait. Nothing about that request is
     * attributable, so the number is advisory only; it matches the counting-failure interval so
     * that neither answer distinguishes itself from the other by its header.
     */
    private static final Duration UNRESOLVED_ADDRESS_RETRY = Duration.ofMinutes(1);

    /**
     * How far back {@link #throttle} reads. A run that began earlier is counted from here: at the
     * slowest pace the default throttle allows, one failure each fifteen minutes, a day still
     * holds ninety-six of them, far past any threshold.
     */
    private static final Duration LOOKBACK = Duration.ofDays(1);

    private final LoginAttemptRepository attempts;
    private final LocalCredentialRepository credentials;
    private final LockoutProperties properties;
    private final SecurityEventRecorder securityEvents;

    public LockoutService(LoginAttemptRepository attempts, LocalCredentialRepository credentials,
                          LockoutProperties properties, SecurityEventRecorder securityEvents) {
        this.attempts = attempts;
        this.credentials = credentials;
        this.properties = properties;
        this.securityEvents = securityEvents;
    }

    /**
     * The per-address gate, taken before any credential is looked at (gap P2-5).
     *
     * <p><strong>Fail-closed, in all three ways it can fail.</strong> If the count cannot be
     * taken the request is refused; if the address is over the threshold it is refused; and if
     * there is <em>no usable address</em> it is refused too. The pre-collapse limiter logged
     * "failing open" here, which turned an outage of the counting store into a way of switching
     * brute-force protection off; on a public-internet login page that is the wrong direction to
     * fail in.
     *
     * <p>The third case is confirmed review finding 5. {@code ClientIpResolver.normalise} answers
     * null for a value that is empty or implausibly long, and Tomcat's {@code RemoteIpValve} does
     * not check that the {@code X-Forwarded-For} entry it settles on is a syntactically valid
     * address — it takes the first entry that is not a configured proxy. Waving those through
     * left the one control that still binds after V12 raised the login rule above it switchable
     * off by whoever could produce an unresolvable address. There is nothing to count them
     * against, so they are refused rather than admitted: an unattributable sign-in attempt is
     * exactly the shape of the traffic this gate exists for.
     *
     * @throws TooManyLoginAttemptsException when this address is over the threshold, when the
     *                                       count could not be taken at all, or when there is no
     *                                       address to count against
     */
    public void assertAddressNotThrottled(String ip, Instant now) {
        if (ip == null || ip.isBlank()) {
            LOG.warn("Refusing a login with no resolvable client address: there is nothing to "
                + "count it against, and admitting it would switch the per-address gate off for "
                + "anyone able to produce one (gap P2-5)");
            throw new TooManyLoginAttemptsException(UNRESOLVED_ADDRESS_RETRY.toSeconds());
        }
        long failures;
        try {
            failures = attempts.countIpFailuresSince(ip, now.minus(properties.ipWindow()));
        } catch (org.springframework.dao.DataAccessException e) {
            LOG.error("Could not count login attempts for an address; refusing the request "
                + "rather than failing open (gap P2-5)", e);
            throw new TooManyLoginAttemptsException(COUNT_FAILURE_RETRY.toSeconds(), e);
        }
        if (failures >= properties.ipThreshold()) {
            LOG.warn("Refusing a login: {} failures from one address inside {}",
                failures, properties.ipWindow());
            securityEvents.record(SecurityEventType.LOCKOUT, null, null,
                Map.of("scope", "ip", "failures", failures));
            throw new TooManyLoginAttemptsException(properties.ipWindow().toSeconds());
        }
    }

    /**
     * Whether a password sign-in to {@code email} from {@code ip} is refused right now, whatever
     * password it carries: this address is throttled for this account, or the account is over
     * its ceiling and this is not an address it has been signed in to from before.
     */
    public boolean isLocked(String email, String ip, Instant now) {
        return throttle(email, ip, now).until() != null
            || overCeiling(email, now) && !attempts.hasSucceededFrom(email, ip);
    }

    /**
     * {@link #isLocked}, for the second half of a two-factor login, where no address is exempt
     * from the ceiling.
     *
     * <p>Whoever reaches the second factor has the password, and a correct password is exactly
     * what makes an address "known" ({@link #recordSuccess} runs on the password half). Exempting
     * known addresses here would let anybody holding the password grind six digits from as many
     * addresses as they like, with nothing but the per-address throttle in the way; ASVS
     * §2.2.1's hundred an hour has to hold for the factor that is supposed to be the stronger
     * one. The owner is refused here too while that lasts, which is the honest outcome: their
     * password is known to somebody else.
     */
    public boolean isLockedForSecondFactor(String email, String ip, Instant now) {
        return throttle(email, ip, now).until() != null || overCeiling(email, now);
    }

    private boolean overCeiling(String email, Instant now) {
        return attempts.countFailuresSince(email, now.minus(properties.accountWindow()))
            >= properties.accountThreshold();
    }

    /**
     * The throttle on this address for this account: how many failures its current run holds,
     * and when it ends ({@code null} when it is not throttled).
     *
     * <p>A run is the failures with no quiet {@code window} between them — quiet counted from
     * the end of the throttle the previous failure set, not from the failure itself. Counting
     * the failures inside a sliding window instead let a run decay while it was being served:
     * an address that waited out each backoff found its oldest failures had left the window, its
     * count back under the threshold, and five free guesses waiting. Measured this way the delay
     * only ever grows until the address goes quiet.
     */
    Throttle throttle(String email, String ip, Instant now) {
        long run = 0;
        Instant until = null;
        Instant quietSince = null;
        for (Instant at : attempts.failuresFromSince(email, ip, now.minus(LOOKBACK))) {
            run = quietSince != null && !at.isAfter(quietSince.plus(properties.window()))
                ? run + 1
                : 1;
            until = run >= properties.threshold() ? at.plus(backoffFor(run)) : null;
            quietSince = until == null ? at : until;
        }
        return new Throttle(run, until != null && until.isAfter(now) ? until : null);
    }

    /** See {@link #throttle}. */
    record Throttle(long failures, Instant until) {
    }

    /**
     * Records a failed attempt, and the throttle it sets, in a transaction of its own.
     *
     * <p><strong>{@code REQUIRES_NEW} is what makes this control exist at all.</strong> Every
     * caller of this method is about to throw {@link InvalidCredentialsException}, and every
     * caller runs inside a transaction. Written inline, the {@code login_attempt} row and the
     * updated {@code locked_until} were both rolled back by the very exception they were
     * recording — so nothing was ever counted, nothing ever locked, and readiness gap P2-5's
     * entire brute-force defence was inert while looking, from the code and from the logs, as
     * though it were working. {@code LockoutIT} is what found that, by counting the rows.
     *
     * <p>The credential is re-read here rather than passed in, because an entity loaded in the
     * caller's persistence context is not managed by this one; sharing it across the boundary
     * would write through a detached instance and reintroduce the same class of silent loss.
     *
     * <p>Callers do not call this for an attempt {@link #isLocked} refused: see the class javadoc.
     *
     * @param userId the account the address resolves to, or {@code null} when it resolves to
     *               none — the attempt is still recorded, because per-address counting has to see
     *               guesses at addresses that do not exist or the cheapest evasion is to make them
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String email, String ip, UUID userId, Instant now) {
        attempts.record(email, ip, false, now);
        if (userId == null) {
            return;
        }
        LocalCredential credential = credentials.findByUserId(userId).orElse(null);
        if (credential == null) {
            return;
        }
        Throttle throttle = throttle(email, ip, now);
        if (throttle.until() == null) {
            credential.recordFailure((int) throttle.failures(), credential.getLockedUntil());
        } else {
            credential.recordFailure((int) throttle.failures(), throttle.until());
            LOG.warn("Sign-in throttled for one address after {} failures: until {}",
                throttle.failures(), throttle.until());
            securityEvents.record(SecurityEventType.LOCKOUT, userId,
                Map.of("scope", "address", "failures", throttle.failures(),
                    "lockedUntilEpochSecond", throttle.until().getEpochSecond()));
        }
        long accountFailures =
            attempts.countFailuresSince(email, now.minus(properties.accountWindow()));
        if (accountFailures == properties.accountThreshold()) {
            LOG.warn("Account reached {} failures in {}: addresses it has not been signed in "
                + "from are throttled", accountFailures, properties.accountWindow());
            securityEvents.record(SecurityEventType.LOCKOUT, userId,
                Map.of("scope", "account", "failures", accountFailures));
        }
        credentials.save(credential);
    }

    /**
     * Ends every throttle on the account by forgetting its failed attempts: an administrator's
     * unlock.
     *
     * @return the failed attempts forgotten
     */
    public int forgetFailures(String email) {
        return attempts.forgetFailures(email);
    }

    /** Clears the counter after a successful authentication. */
    public void recordSuccess(String email, String ip, LocalCredential credential, Instant now) {
        attempts.record(email, ip, true, now);
        if (credential != null && (credential.getFailedAttempts() > 0
            || credential.getLockedUntil() != null)) {
            credential.clearLock();
        }
    }

    /**
     * The lock duration for the {@code n}th failure past the threshold: base, 2×base, 4×base …
     * capped at {@code maxBackoff}.
     */
    Duration backoffFor(long failures) {
        long steps = Math.max(0, failures - properties.threshold());
        // 2^62 overflows a long; the cap is reached long before that, but the clamp keeps the
        // arithmetic total rather than relying on it.
        long multiplier = steps >= 62 ? Long.MAX_VALUE : 1L << steps;
        Duration scaled = multiplier >= properties.maxBackoff().toSeconds()
            ? properties.maxBackoff()
            : properties.baseBackoff().multipliedBy(multiplier);
        return scaled.compareTo(properties.maxBackoff()) > 0 ? properties.maxBackoff() : scaled;
    }

    public LockoutProperties properties() {
        return properties;
    }
}
