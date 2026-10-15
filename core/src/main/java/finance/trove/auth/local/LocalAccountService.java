package finance.trove.auth.local;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password storage and verification (gaps P2-4 and P2-5).
 *
 * <p><strong>Enumeration safety is the design, not a flag.</strong> {@link #authenticate} takes
 * the same path for an address that exists and one that does not: it always performs exactly one
 * argon2id verification, against the real hash when there is one and against a fixed dummy hash
 * when there is not. Without the dummy, the "no such user" branch returns in microseconds while
 * the "wrong password" branch takes ~100 ms, and the difference is a reliable oracle for "does
 * this person bank here?" — measurable over the internet, not just on a LAN. The response body,
 * status and slug are identical in every failure case for the same reason (§1.5).
 *
 * <p>The dummy hash is computed once at construction from a random secret, using the configured
 * parameters, so its cost tracks the real one. Hard-coding a PHC string would silently stop
 * matching the moment the memory cost was tuned.
 *
 * <p><strong>Locked accounts fail identically.</strong> A lock is not reported to the caller; it
 * simply never succeeds. That is what stops an attacker using the lock message as confirmation
 * that they have found a real account.
 */
@Service
public class LocalAccountService {

    private static final Logger LOG = LoggerFactory.getLogger(LocalAccountService.class);

    private final UserRepository users;
    private final LocalCredentialRepository credentials;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final LockoutService lockout;
    private final SecurityEventRecorder securityEvents;
    private final String dummyHash;

    public LocalAccountService(UserRepository users, LocalCredentialRepository credentials,
                               PasswordEncoder encoder, PasswordPolicy policy,
                               LockoutService lockout, SecurityEventRecorder securityEvents) {
        this.users = users;
        this.credentials = credentials;
        this.encoder = encoder;
        this.policy = policy;
        this.lockout = lockout;
        this.securityEvents = securityEvents;
        this.dummyHash = encoder.encode(randomSecret());
    }

    /** Validates against the policy and returns the argon2id PHC string. */
    public String hashNewPassword(String rawPassword, String email) {
        policy.validate(rawPassword, email);
        return encoder.encode(rawPassword);
    }

    /** Creates the credential row for a newly registered or bootstrapped user. */
    @Transactional
    public void createCredential(UUID userId, String rawPassword, String email, Instant now) {
        credentials.save(new LocalCredential(userId, hashNewPassword(rawPassword, email), now));
    }

    /**
     * Verifies a password and, on success, returns the user.
     *
     * @throws InvalidCredentialsException for every failure — unknown address, wrong password,
     *                                     locked account, deactivated account, or an account with
     *                                     no password (OIDC-only). One exception, by design.
     */
    @Transactional
    public User authenticate(String email, String rawPassword, String ip, Instant now) {
        Optional<User> maybeUser = users.findByEmailIgnoreCase(email);
        Optional<LocalCredential> maybeCredential =
            maybeUser.flatMap(u -> credentials.findByUserId(u.getId()));

        String hash = maybeCredential.map(LocalCredential::getPasswordHash).orElse(dummyHash);
        // Always exactly one verification, whatever the outcome. Do not short-circuit this on a
        // locked account either: skipping it would make a locked account measurably faster than
        // a wrong password, which reveals the lock the response deliberately hides.
        boolean passwordMatches = encoder.matches(rawPassword, hash);

        User user = maybeUser.orElse(null);
        LocalCredential credential = maybeCredential.orElse(null);
        UUID userId = user == null ? null : user.getId();

        boolean active = user != null && user.isActive();

        if (lockout.isLocked(email, ip, now)) {
            // Not recorded as a failure: the password was never considered, so this was not a
            // guess, and counting it would only stretch the throttle of an owner who retried
            // too soon (LockoutService's class javadoc).
            securityEvents.record(SecurityEventType.LOGIN_FAIL, userId, userId,
                Map.of("reason", "rejected"));
            throw new InvalidCredentialsException();
        }
        if (!passwordMatches || !active || credential == null) {
            // Both of these commit independently of this method: it is about to throw, and a
            // rollback would erase the very evidence being recorded. LockoutService#recordFailure
            // re-reads and saves the credential itself for the same reason.
            lockout.recordFailure(email, ip, userId, now);
            securityEvents.record(SecurityEventType.LOGIN_FAIL, userId, userId,
                Map.of("reason", "rejected"));
            LOG.debug("Login rejected for a candidate address");
            throw new InvalidCredentialsException();
        }

        if (encoder.upgradeEncoding(hash)) {
            // The cost parameters were raised since this password was set. Re-hash now, while the
            // plaintext is briefly in hand; the alternative is to leave every long-standing
            // account permanently at the old, weaker cost.
            credential.rehash(encoder.encode(rawPassword));
            LOG.info("Re-hashed a password at the current argon2id parameters");
        }
        lockout.recordSuccess(email, ip, credential, now);
        credentials.save(credential);
        return user;
    }

    /**
     * Verifies a password for a user already identified — the re-auth half of a step-up, and the
     * "current password" check on a password change. Deliberately does not touch the lockout
     * counters: the caller is already authenticated, so this is not an anonymous guessing surface.
     */
    public boolean matchesCurrentPassword(UUID userId, String rawPassword) {
        String hash = credentials.findByUserId(userId)
            .map(LocalCredential::getPasswordHash)
            .orElse(dummyHash);
        return encoder.matches(rawPassword, hash);
    }

    /** Replaces a password, clearing any lock. Caller is responsible for revoking sessions. */
    @Transactional
    public void setPassword(UUID userId, String rawPassword, String email, Instant now) {
        LocalCredential credential = credentials.findByUserId(userId)
            .orElseGet(() -> new LocalCredential(userId, dummyHash, now));
        credential.setPasswordHash(hashNewPassword(rawPassword, email), now);
        credentials.save(credential);
    }

    /**
     * Admin unlock (gap P2-5), also run by a password reset: ends every throttle on the account,
     * from every address, without changing the password.
     *
     * @return whether the account has a password to sign in with
     */
    @Transactional
    public boolean unlock(UUID userId) {
        users.findAccount(userId).ifPresent(user -> lockout.forgetFailures(user.getEmail()));
        return credentials.findByUserId(userId)
            .map(credential -> {
                credential.clearLock();
                credentials.save(credential);
                return true;
            })
            .orElse(false);
    }

    public boolean hasPassword(UUID userId) {
        return credentials.findByUserId(userId).isPresent();
    }

    public Optional<Instant> lockedUntil(UUID userId) {
        return credentials.findByUserId(userId).map(LocalCredential::getLockedUntil);
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
