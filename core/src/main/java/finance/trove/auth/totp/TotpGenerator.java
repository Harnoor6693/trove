package finance.trove.auth.totp;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * RFC 6238 time-based one-time passwords, plus the RFC 4648 base32 the {@code otpauth://} URI
 * carries the shared secret in (gap P2-6).
 *
 * <p>Written rather than pulled in: the whole algorithm is HMAC-SHA-1 over a big-endian counter
 * followed by RFC 4226's dynamic truncation, and the alternative is a dependency whose base32
 * codec and clock handling would still have to be read to be trusted. Everything here is
 * verifiable against the RFCs' own test vectors, which {@code TotpGeneratorTest} uses.
 *
 * <p>Base32 (not base64, not hex) because that is what every authenticator app's manual-entry
 * field accepts and what the {@code otpauth://} scheme specifies.
 *
 * <p>Verification is constant-time on the digit comparison. The window is deliberately narrow —
 * one step either side — and callers additionally refuse a step that has already been used
 * ({@code totp_credential.last_used_step}), so a code observed in transit cannot be replayed
 * inside the thirty seconds it remains numerically valid.
 */
@Component
public class TotpGenerator {

    private static final String HMAC_ALGORITHM = "HmacSHA1";
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int SECRET_BYTES = 20;

    private final SecureRandom random = new SecureRandom();

    /** A fresh 160-bit secret, base32-encoded — the size RFC 4226 recommends for HMAC-SHA-1. */
    public String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /** The RFC 6238 time step {@code instant} falls in. */
    public long timeStep(Instant instant, int periodSeconds) {
        return Math.floorDiv(instant.getEpochSecond(), (long) periodSeconds);
    }

    /** The code for one specific step. */
    public String codeAt(String base32Secret, long step, int digits) {
        byte[] key = base32Decode(base32Secret);
        byte[] counter = new byte[8];
        long value = step;
        for (int i = 7; i >= 0; i--) {
            counter[i] = (byte) (value & 0xff);
            value >>>= 8;
        }
        byte[] hmac = hmacSha1(key, counter);
        int offset = hmac[hmac.length - 1] & 0x0f;
        int binary = ((hmac[offset] & 0x7f) << 24)
            | ((hmac[offset + 1] & 0xff) << 16)
            | ((hmac[offset + 2] & 0xff) << 8)
            | (hmac[offset + 3] & 0xff);
        int modulus = (int) Math.pow(10, digits);
        return String.format(Locale.ROOT, "%0" + digits + "d", binary % modulus);
    }

    /**
     * The step at which {@code candidate} is valid, or {@code -1}.
     *
     * <p>Returning the step rather than a boolean is what lets the caller enforce the replay
     * guard: it can compare the matched step against the last one it accepted for that user.
     *
     * @param minimumStep steps at or below this are refused outright (already used)
     */
    public long verify(String base32Secret, String candidate, Instant now, int periodSeconds,
                       int digits, int allowedDrift, long minimumStep) {
        if (candidate == null) {
            return -1L;
        }
        String normalised = candidate.trim().replace(" ", "");
        if (normalised.length() != digits) {
            return -1L;
        }
        long current = timeStep(now, periodSeconds);
        for (long step = current - allowedDrift; step <= current + allowedDrift; step++) {
            if (step <= minimumStep) {
                continue;
            }
            if (constantTimeEquals(codeAt(base32Secret, step, digits), normalised)) {
                return step;
            }
        }
        return -1L;
    }

    /**
     * The {@code otpauth://} URI an authenticator app scans.
     *
     * <p>The label carries the issuer twice — once as the {@code Issuer:Account} prefix and once
     * as a query parameter — which is what the de-facto Google Authenticator specification asks
     * for, and what makes the entry readable as "Trove (alice@example.com)" rather than as a bare
     * address in a list of thirty.
     */
    public String otpauthUri(String issuer, String accountName, String base32Secret, int digits,
                             int periodSeconds) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
            + "?secret=" + base32Secret
            + "&issuer=" + urlEncode(issuer)
            + "&algorithm=SHA1"
            + "&digits=" + digits
            + "&period=" + periodSeconds;
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32_ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1f));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32_ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] base32Decode(String encoded) {
        String cleaned = encoded.trim().replace("=", "").replace(" ", "")
            .toUpperCase(Locale.ROOT);
        int buffer = 0;
        int bitsLeft = 0;
        byte[] out = new byte[cleaned.length() * 5 / 8];
        int index = 0;
        for (int i = 0; i < cleaned.length(); i++) {
            int value = BASE32_ALPHABET.indexOf(cleaned.charAt(i));
            if (value < 0) {
                throw new IllegalArgumentException("Not a base32 character: " + cleaned.charAt(i));
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out[index++] = (byte) ((buffer >> (bitsLeft - 8)) & 0xff);
                bitsLeft -= 8;
            }
        }
        return out;
    }

    private static byte[] hmacSha1(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is required by the JRE specification", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
