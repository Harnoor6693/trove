package finance.trove.notifications.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What a receiver does with a delivery, written from the Standard Webhooks spec
 * (https://www.standardwebhooks.com) rather than from {@link WebhookSignature}, so that the tests
 * check the wire format against the published scheme and not against itself.
 *
 * <p>The steps are the spec's: reject a {@code webhook-timestamp} more than five minutes from
 * now, HMAC-SHA256 {@code id.timestamp.body} with the shared secret, and accept when any
 * space-separated {@code v1,<base64>} entry of {@code webhook-signature} matches in constant time.
 * Trove's secret is the member's own string, so the key is its UTF-8 bytes.
 */
final class StandardWebhooksReceiver {

    /** The spec's replay window. */
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private StandardWebhooksReceiver() {
    }

    static boolean accepts(String secret, String id, String timestamp, String signatureHeader,
                           byte[] body, Instant now) {
        if (id == null || timestamp == null || signatureHeader == null) {
            return false;
        }
        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(now.getEpochSecond() - sentAt) > TOLERANCE.toSeconds()) {
            return false;
        }
        byte[] expected = hmac(secret.getBytes(StandardCharsets.UTF_8),
            (id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8), body);
        for (String entry : signatureHeader.split(" ")) {
            int comma = entry.indexOf(',');
            if (comma > 0 && "v1".equals(entry.substring(0, comma))
                && MessageDigest.isEqual(expected,
                    Base64.getDecoder().decode(entry.substring(comma + 1)))) {
                return true;
            }
        }
        return false;
    }

    static byte[] hmac(byte[] key, byte[] prefix, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(prefix);
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
