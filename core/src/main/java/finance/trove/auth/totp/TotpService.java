package finance.trove.auth.totp;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.users.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enrolment, verification, recovery and removal of the second factor (gap P2-6).
 *
 * <p>The lifecycle is deliberately three steps, not two:
 * <ol>
 *   <li>{@link #beginEnrolment} — the caller has just re-entered their password; a secret is
 *       generated, encrypted and stored with {@code enabled=false}.</li>
 *   <li>{@link #confirmEnrolment} — one live code must verify before {@code enabled} becomes
 *       true. Only now are the ten recovery codes generated and shown once.</li>
 *   <li>{@link #disable} — password plus a current code, or an admin acting for a member who
 *       lost their phone.</li>
 * </ol>
 * Skipping step 2 is what locks a user out of their own finance server forever, which is why the
 * schema itself refuses {@code enabled=true} without a {@code confirmed_at}.
 *
 * <p>Recovery codes are shown exactly once, stored as SHA-256 hashes, and removed as they are
 * used. There is no "show my codes again": a code that can be re-displayed is a password, and it
 * would be a password stored in a form the server can read.
 */
@Service
public class TotpService {

    private static final Logger LOG = LoggerFactory.getLogger(TotpService.class);

    /**
     * Recovery-code alphabet with the ambiguous glyphs removed (no {@code I}, {@code L},
     * {@code O}, {@code 0}, {@code 1}): these are read off a screen and typed under pressure,
     * often months later.
     */
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int RECOVERY_GROUP = 5;
    private static final int RECOVERY_GROUPS = 2;

    private final TotpCredentialRepository credentials;
    private final TotpGenerator generator;
    private final FieldEncryptionService encryption;
    private final TotpProperties properties;
    private final SecurityEventRecorder securityEvents;
    private final SecureRandom random = new SecureRandom();

    public TotpService(TotpCredentialRepository credentials, TotpGenerator generator,
                       FieldEncryptionService encryption, TotpProperties properties,
                       SecurityEventRecorder securityEvents) {
        this.credentials = credentials;
        this.generator = generator;
        this.encryption = encryption;
        this.properties = properties;
        this.securityEvents = securityEvents;
    }

    /** Whether this user must complete a TOTP step to finish logging in. */
    public boolean isEnabled(UUID userId) {
        return credentials.findByUserId(userId)
            .map(TotpCredential::isEnabled)
            .orElse(false);
    }

    /**
     * Starts (or restarts) enrolment. The caller must already have re-authenticated with a
     * password — {@code AuthController} enforces that, not this method, because "which
     * re-authentication counts" is a routing concern.
     *
     * @throws TotpException if TOTP is already enabled; re-enrolling silently would replace a
     *                       working factor with an unproven one
     */
    @Transactional
    public TotpEnrolment beginEnrolment(User user) {
        TotpCredential credential = credentials.findByUserId(user.getId()).orElse(null);
        if (credential != null && credential.isEnabled()) {
            throw TotpException.alreadyEnabled();
        }
        String secret = generator.generateSecret();
        String encrypted = encryption.encrypt(secret);
        if (credential == null) {
            credential = new TotpCredential(user.getId(), encrypted);
        } else {
            credential.resetSecret(encrypted);
        }
        credentials.save(credential);
        return new TotpEnrolment(
            secret,
            generator.otpauthUri(properties.issuer(), user.getEmail(), secret,
                properties.digits(), properties.periodSeconds()),
            properties.digits(),
            properties.periodSeconds());
    }

    /**
     * Verifies one live code and enables the factor.
     *
     * @return the ten recovery codes, in plaintext, for the only time they will ever be legible
     */
    @Transactional
    public List<String> confirmEnrolment(UUID userId, String code, Instant now) {
        TotpCredential credential = credentials.findByUserId(userId)
            .orElseThrow(TotpException::notEnrolled);
        if (credential.isEnabled()) {
            throw TotpException.alreadyEnabled();
        }
        long step = matchStep(credential, code, now);
        if (step < 0) {
            throw TotpException.invalidCode();
        }
        List<String> plain = generateRecoveryCodes();
        credential.setLastUsedStep(step);
        credential.enable(now, plain.stream().map(TotpService::hash).toList());
        credentials.save(credential);
        securityEvents.record(SecurityEventType.TOTP_ENROL, userId,
            Map.of("recoveryCodes", plain.size()));
        LOG.info("TOTP enabled for a user after a verified code");
        return plain;
    }

    /**
     * The login-time and step-up check. Accepts either a live TOTP code or an unused recovery
     * code, and spends whichever it accepted.
     *
     * <p>The spend is the repository's conditional update, not a save of the entity read here:
     * that read is unlocked, so two requests carrying the same code both pass the match, and only
     * the database can say which of them used it first. The entity is never modified, so nothing
     * flushes its now-stale copy of the row over the one the update wrote.
     *
     * @return {@code true} on success; the caller decides what that authorises
     */
    @Transactional
    public boolean verify(UUID userId, String code, Instant now) {
        Optional<TotpCredential> maybe = credentials.findByUserId(userId);
        if (maybe.isEmpty() || !maybe.get().isEnabled()) {
            return false;
        }
        TotpCredential credential = maybe.get();
        long step = matchStep(credential, code, now);
        if (step >= 0) {
            return credentials.claimStep(userId, step) == 1;
        }
        if (code != null
            && credentials.consumeRecoveryCode(userId, hash(normaliseRecovery(code))) == 1) {
            int remaining = credential.remainingRecoveryCodes() - 1;
            LOG.warn("A recovery code was used; {} remain", remaining);
            securityEvents.record(SecurityEventType.LOGIN_OK, userId,
                Map.of("factor", "recovery-code", "remainingRecoveryCodes", remaining));
            return true;
        }
        return false;
    }

    /** How many unused recovery codes remain, for the settings screen. */
    public int remainingRecoveryCodes(UUID userId) {
        return credentials.findByUserId(userId)
            .map(TotpCredential::remainingRecoveryCodes)
            .orElse(0);
    }

    /**
     * Removes the factor.
     *
     * @param actorId who performed it — equal to {@code userId} for a self-service disable, the
     *                admin's id when an admin clears a member's factor. The distinction is the
     *                entire value of the resulting {@code security_event} row.
     */
    @Transactional
    public void disable(UUID userId, UUID actorId) {
        credentials.deleteByUserId(userId);
        securityEvents.record(SecurityEventType.TOTP_DISABLE, userId, actorId,
            Map.of("selfService", userId.equals(actorId)));
    }

    private long matchStep(TotpCredential credential, String code, Instant now) {
        if (code == null || code.isBlank()) {
            return -1L;
        }
        String secret = encryption.decrypt(credential.getSecretEncrypted());
        return generator.verify(secret, code, now, properties.periodSeconds(),
            properties.digits(), properties.allowedDrift(), credential.getLastUsedStep());
    }

    private List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(properties.recoveryCodes());
        for (int i = 0; i < properties.recoveryCodes(); i++) {
            StringBuilder code = new StringBuilder();
            for (int group = 0; group < RECOVERY_GROUPS; group++) {
                if (group > 0) {
                    code.append('-');
                }
                for (int c = 0; c < RECOVERY_GROUP; c++) {
                    code.append(RECOVERY_ALPHABET.charAt(
                        random.nextInt(RECOVERY_ALPHABET.length())));
                }
            }
            codes.add(code.toString());
        }
        return List.copyOf(codes);
    }

    /** Recovery codes are compared case- and separator-insensitively; people retype them. */
    static String normaliseRecovery(String code) {
        return code.trim().toUpperCase(Locale.ROOT).replace(" ", "");
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE specification", e);
        }
    }
}
