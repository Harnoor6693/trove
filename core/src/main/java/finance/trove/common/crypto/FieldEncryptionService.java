package finance.trove.common.crypto;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM encryption for the individual database columns that hold a secret: the SimpleFIN
 * access URL, a user's AI API key, a TOTP secret, an OIDC state cookie
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5/§5, gap P5-27).
 *
 * <p>Moved from {@code services/ingestion-service} and rewritten in three ways:
 * <ul>
 *   <li><strong>Versioned ciphertext.</strong> Output is {@code v1:BASE64(iv‖ciphertext‖tag)}.
 *       The source format was bare base64, which makes key rotation impossible: nothing in a
 *       stored value says which key encrypted it, so a rotation has to re-encrypt every row in
 *       one transaction or lose the ones it misses. With a version prefix, a future {@code v2}
 *       key can be introduced and rows migrated lazily on next read (gap P5-27).</li>
 *   <li><strong>Key from configuration, validated at startup.</strong> The key is bound from
 *       {@code trove.crypto.key} ({@code TROVE_ENCRYPTION_KEY}) and checked in the constructor,
 *       so a missing or malformed key fails the context refresh with a sentence an operator can
 *       act on — not an {@code IllegalStateException} on the first bank sync, days later. There
 *       is no default and no fallback: §9 risk 16. A key that is one byte repeated (the
 *       all-zero default of {@code application-dev.yml} among them) is publicly known, so only
 *       the {@code dev} profile accepts one.</li>
 *   <li><strong>One {@link SecureRandom}.</strong> The source constructed a new one per call,
 *       which on a cold JVM can block on entropy for the length of an import.</li>
 * </ul>
 *
 * <p><strong>Trust boundary.</strong> Plaintext exists only on the heap for the duration of a
 * call. It is never logged, never returned in an API response, and never written to disk. The
 * 96-bit IV is fresh per call (GCM's one fatal misuse is IV reuse under the same key) and the
 * 128-bit tag makes any bit-flip in the stored column a hard failure rather than garbage
 * plaintext.
 */
@Service
public class FieldEncryptionService {

    /** Current ciphertext version; the prefix every value this service writes carries. */
    public static final String CURRENT_VERSION = "v1";

    private static final Logger LOG = LoggerFactory.getLogger(FieldEncryptionService.class);

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final char VERSION_SEPARATOR = ':';

    private static final String KEY_HELP =
        "TROVE_ENCRYPTION_KEY must be a 256-bit key: 64 hex characters or base64 of exactly 32 "
            + "bytes. Generate one with `openssl rand -hex 32`. There is no default.";

    /** The only profile that may run on a publicly known key: application-dev.yml's default. */
    private static final String DEV_PROFILE = "dev";

    private final SecureRandom random = new SecureRandom();
    private final byte[] keyBytes;

    /**
     * The constructor Spring uses. Annotated because the class has two, and without the
     * annotation the container refuses to pick one — the failure is at context refresh, which is
     * where a key problem belongs, but the message would be about constructors rather than about
     * the key.
     */
    @Autowired
    public FieldEncryptionService(CryptoProperties properties, Environment environment) {
        this(properties == null ? null : properties.key(),
            environment != null && environment.acceptsProfiles(Profiles.of(DEV_PROFILE)));
    }

    /** Direct-key constructor, for tests and for the {@code trove} CLI. */
    public FieldEncryptionService(String key) {
        this(key, false);
    }

    private FieldEncryptionService(String key, boolean devProfile) {
        this.keyBytes = parseKey(key);
        if (isOneRepeatedByte(keyBytes)) {
            if (!devProfile) {
                throw new IllegalStateException("TROVE_ENCRYPTION_KEY is one byte repeated 32 "
                    + "times (application-dev.yml's all-zero default is one), a key anyone can "
                    + "guess: every bank credential, AI key and TOTP secret encrypted with it is "
                    + "readable from a database dump. Only the dev profile accepts it. "
                    + KEY_HELP);
            }
            LOG.warn("TROVE_ENCRYPTION_KEY is a publicly known key (one byte repeated); "
                + "accepted only because the dev profile is active");
        }
    }

    /**
     * Encrypts {@code plaintext}.
     *
     * @param plaintext the value to protect; must not be {@code null}
     * @return {@code v1:} followed by base64 of {@code iv ‖ ciphertext ‖ tag}
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, KEY_ALGORITHM),
                new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(sealed, 0, combined, iv.length, sealed.length);
            return CURRENT_VERSION + VERSION_SEPARATOR + Base64.getEncoder()
                .encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}, verifying the GCM tag before any plaintext
     * is returned.
     *
     * @throws EncryptionException if the value is not a recognised version, is truncated, was
     *                             tampered with, or was encrypted under a different key
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isEmpty()) {
            throw new IllegalArgumentException("ciphertext must not be null or empty");
        }
        String version = versionOf(ciphertext);
        if (version == null) {
            throw new EncryptionException(
                "Ciphertext carries no version prefix; expected " + CURRENT_VERSION + ":");
        }
        if (!CURRENT_VERSION.equals(version)) {
            throw new EncryptionException("Unsupported ciphertext version '" + version
                + "'; this build understands " + CURRENT_VERSION);
        }
        byte[] combined = decodeBody(ciphertext.substring(version.length() + 1));
        // An empty plaintext is legitimate and seals to exactly IV + tag, so the floor is
        // "shorter than that", not "no longer than that".
        if (combined.length < IV_LENGTH_BYTES + (TAG_LENGTH_BITS / 8)) {
            throw new EncryptionException("Ciphertext is truncated: " + combined.length
                + " bytes cannot hold an IV and a tag");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, KEY_ALGORITHM),
                new GCMParameterSpec(TAG_LENGTH_BITS,
                    Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES)));
            byte[] plain = cipher.doFinal(
                Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            LOG.error("Field decryption failed the authentication tag check — the stored value "
                + "was modified, or TROVE_ENCRYPTION_KEY has changed since it was written");
            throw new EncryptionException("Decryption failed authentication", e);
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Decryption failed", e);
        }
    }

    /** Whether {@code value} looks like something this service wrote. */
    public boolean isEncrypted(String value) {
        return versionOf(value) != null;
    }

    /**
     * The version prefix of {@code value}, or {@code null} if it carries none. Used by the lazy
     * rotation path: a row whose version is older than {@link #CURRENT_VERSION} is decrypted with
     * the old key and rewritten with the current one.
     */
    public String versionOf(String value) {
        if (value == null) {
            return null;
        }
        int separator = value.indexOf(VERSION_SEPARATOR);
        if (separator < 2 || separator > 4 || value.charAt(0) != 'v') {
            return null;
        }
        String candidate = value.substring(0, separator);
        for (int i = 1; i < candidate.length(); i++) {
            if (!Character.isDigit(candidate.charAt(i))) {
                return null;
            }
        }
        return candidate;
    }

    /** Generates a fresh key in the base64 form this service accepts. */
    public static String generateKey() {
        byte[] key = new byte[KEY_LENGTH_BYTES];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * Best-effort key erasure on shutdown. The JCE may still hold copies inside initialised
     * {@code Cipher} objects, so this narrows the window rather than closing it.
     */
    @PreDestroy
    void destroy() {
        Arrays.fill(keyBytes, (byte) 0);
    }

    private static byte[] decodeBody(String body) {
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new EncryptionException("Ciphertext body is not valid base64", e);
        }
    }

    /**
     * Accepts the two forms an operator plausibly produces: {@code openssl rand -hex 32} (64 hex
     * characters, the form §1.6 documents) and {@code openssl rand -base64 32}.
     */
    private static byte[] parseKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("TROVE_ENCRYPTION_KEY is not set. " + KEY_HELP);
        }
        String trimmed = key.trim();
        if (trimmed.startsWith("${")) {
            throw new IllegalStateException(
                "TROVE_ENCRYPTION_KEY resolved to the literal placeholder '" + trimmed
                    + "', which means the variable is absent from the environment. " + KEY_HELP);
        }
        if (trimmed.length() == KEY_LENGTH_BYTES * 2 && isHex(trimmed)) {
            return HexFormat.of().parseHex(trimmed.toLowerCase(Locale.ROOT));
        }
        byte[] decoded = tryBase64(trimmed);
        if (decoded == null) {
            throw new IllegalStateException("TROVE_ENCRYPTION_KEY is neither 64 hex characters "
                + "nor valid base64. " + KEY_HELP);
        }
        if (decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("TROVE_ENCRYPTION_KEY decodes to " + decoded.length
                + " bytes; AES-256 needs exactly " + KEY_LENGTH_BYTES + ". " + KEY_HELP);
        }
        return decoded;
    }

    private static boolean isOneRepeatedByte(byte[] key) {
        for (byte b : key) {
            if (b != key[0]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] tryBase64(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
