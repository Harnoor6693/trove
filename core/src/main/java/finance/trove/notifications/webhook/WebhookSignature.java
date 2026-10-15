package finance.trove.notifications.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The HMAC-SHA256 signature every webhook delivery carries.
 *
 * <p>Three headers go out with each POST, and this class produces the first:
 * <ul>
 *   <li>{@code X-Trove-Signature: sha256=<hex>} — an HMAC-SHA256 over the EXACT bytes of the
 *       request body, keyed with the member's own secret. Over the bytes, not over a
 *       re-serialisation of the payload: a receiver that verifies a signature has to hash what it
 *       received, and any difference in whitespace or key order between what was signed and what
 *       was sent makes every delivery look forged.</li>
 *   <li>{@code X-Trove-Event} — the event type, so a receiver can route without parsing.</li>
 *   <li>{@code X-Trove-Delivery} — the delivery row's id, stable across retries, so a receiver can
 *       deduplicate a redelivery it has already acted on.</li>
 * </ul>
 *
 * <p>The prefix is part of the value rather than a separate header so that a future {@code v2}
 * algorithm can be introduced without a receiver having to guess which one it is looking at.
 */
public final class WebhookSignature {

    /** The header the signature travels in. */
    public static final String SIGNATURE_HEADER = "X-Trove-Signature";

    /** The header carrying the event type. */
    public static final String EVENT_HEADER = "X-Trove-Event";

    /** The header carrying the delivery id — stable across retries, for receiver-side dedup. */
    public static final String DELIVERY_HEADER = "X-Trove-Delivery";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private WebhookSignature() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * {@code sha256=<lowercase hex>} over {@code body}, keyed with {@code secret}.
     *
     * @param secret the member's own shared secret, already decrypted
     * @param body   the exact bytes that will be sent
     */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every Java platform, so this is a broken JVM rather than
            // a condition a caller can handle.
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
