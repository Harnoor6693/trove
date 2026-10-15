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
 * Turns a stream of failed logins into a lock (gap P2-5).
 *
 * <p>Counting is done against {@code login_attempt} rather than against the credential row's
 * counter, because the counter alone cannot express "five failures <em>in fifteen minutes</em>":
 * a naive counter locks out an account that fails once a month for five months. The credential
 * row still carries {@code failed_attempts} and {@code locked_until} — schema V4 specifies both,
 * and they are what the login path reads on its fast path, without a second query.
 *
 * <p>The backoff doubles per failure past the threshold and is capped, then gives way to a long
 * hard lock. The shape matters more than the numbers: an attacker's throughput collapses while a
 * user who mistyped their password twice waits half a minute.
 *
 * <p>Nothing here is ever visible to the caller. {@code LocalAccountService} throws the same
 * {@link InvalidCredentialsException} whether the account is locked or the password was simply
 * wrong (§1.5).
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
     * Records a failed attempt and updates the lock state, in a transaction of its own.
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
        long failures = attempts.countFailuresSince(email, now.minus(properties.window()));
        if (failures < properties.threshold()) {
            credential.recordFailure((int) failures, credential.getLockedUntil());
        } else {
            Duration lock = failures >= properties.hardThreshold()
                ? properties.hardLock()
                : backoffFor(failures);
            Instant lockedUntil = now.plus(lock);
            credential.recordFailure((int) failures, lockedUntil);
            LOG.warn("Account soft-locked after {} failures in {}: locked until {}",
                failures, properties.window(), lockedUntil);
            securityEvents.record(SecurityEventType.LOCKOUT, userId,
                Map.of("failures", failures,
                    "lockedUntilEpochSecond", lockedUntil.getEpochSecond()));
        }
        credentials.save(credential);
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
        // 2^62 overflows a long; nothing near that is reachable before the hard lock takes over,
        // but the clamp keeps the arithmetic total rather than relying on that.
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
