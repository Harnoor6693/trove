package finance.trove.notifications.webhook;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signature every webhook delivery carries, in the Standard Webhooks scheme
 * (https://www.standardwebhooks.com, symmetric {@code v1}).
 *
 * <p>Four headers go out with each POST:
 * <ul>
 *   <li>{@code webhook-id}: the delivery row's id, stable across retries, so a receiver can
 *       drop a redelivery it has already acted on.</li>
 *   <li>{@code webhook-timestamp}: Unix seconds at the moment of THIS attempt, so a receiver
 *       can refuse a captured request replayed later. The scheme's window is five minutes.</li>
 *   <li>{@code webhook-signature}: {@code v1,<base64>}, an HMAC-SHA256 keyed with the member's
 *       own secret over {@code <webhook-id>.<webhook-timestamp>.<body>}. The body is the EXACT
 *       bytes sent, not a re-serialisation: a receiver has to hash what it received.</li>
 *   <li>{@code X-Trove-Event}: the event type, so a receiver can route without parsing.</li>
 * </ul>
 *
 * <p>B3-03: the earlier {@code X-Trove-Signature: sha256=<hex>} covered the body alone. The id
 * and the time were unsigned, so a captured delivery stayed valid for ever and its id could be
 * changed to slip a replay past the receiver's dedup.
 *
 * <p>The key is the UTF-8 bytes of the member's secret, as typed. Standard Webhooks libraries
 * expect a base64 {@code whsec_} secret by default, so a receiver using one passes the raw bytes
 * instead (in JavaScript, {@code new Webhook(new TextEncoder().encode(secret),
 * { format: "raw" })}).
 */
public final class WebhookSignature {

    /** The header carrying the delivery id, stable across retries. */
    public static final String ID_HEADER = "webhook-id";

    /** The header carrying the attempt's Unix time in seconds. */
    public static final String TIMESTAMP_HEADER = "webhook-timestamp";

    /** The header the signature travels in. */
    public static final String SIGNATURE_HEADER = "webhook-signature";

    /** The header carrying the event type. */
    public static final String EVENT_HEADER = "X-Trove-Event";

    private static final String ALGORITHM = "HmacSHA256";
    private static final String VERSION = "v1,";

    private WebhookSignature() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * {@code v1,<base64>} over {@code <deliveryId>.<timestamp>.<body>}, keyed with
     * {@code secret}.
     *
     * @param secret     the member's own shared secret, already decrypted
     * @param deliveryId the value sent as {@code webhook-id}
     * @param timestamp  the value sent as {@code webhook-timestamp}, Unix seconds
     * @param body       the exact bytes that will be sent
     */
    public static String sign(String secret, String deliveryId, long timestamp, byte[] body) {
        return sign(secret.getBytes(StandardCharsets.UTF_8), deliveryId, timestamp, body);
    }

    /** The same, over raw key bytes, so the scheme's published test vector can be checked. */
    static String sign(byte[] key, String deliveryId, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update((deliveryId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every Java platform, so this is a broken JVM rather than
            // a condition a caller can handle.
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
