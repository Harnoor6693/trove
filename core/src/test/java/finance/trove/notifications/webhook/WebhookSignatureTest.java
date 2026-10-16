package finance.trove.notifications.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signature a receiver verifies: Standard Webhooks {@code v1}, over the delivery id, the
 * attempt's timestamp and the exact bytes on the wire (B3-03).
 */
class WebhookSignatureTest {

    private static final String SECRET = "a-long-enough-webhook-secret";
    private static final String ID = "4f1c9c1e-6a51-4b3b-9d55-1b2f0b8e7a10";
    private static final long TIMESTAMP = 1_788_768_000L;
    private static final Instant SENT_AT = Instant.ofEpochSecond(TIMESTAMP);

    @Test
    @DisplayName("the published Standard Webhooks test vector signs to the published value")
    void theStandardWebhooksTestVectorMatches() {
        // From the Standard Webhooks reference libraries: secret
        // whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw (base64 after the prefix), this id, this
        // timestamp and this exact body. Matching it is what makes the scheme the published one
        // rather than one that merely looks like it.
        byte[] key = Base64.getDecoder().decode("MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");
        byte[] body = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSignature.sign(key, "msg_p5jXN8AQM9LWM0D4loKWxJek", 1_614_265_330L, body))
            .isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    @DisplayName("the key is the member's secret as typed, so a receiver can verify with it")
    void aReceiverVerifiesWithTheSecretAsTyped() {
        byte[] body = "{\"event\":\"budget.threshold_crossed\",\"amount\":\"12.34\"}"
            .getBytes(StandardCharsets.UTF_8);

        String signature = WebhookSignature.sign(SECRET, ID, TIMESTAMP, body);

        assertThat(signature).startsWith("v1,");
        assertThat(StandardWebhooksReceiver.accepts(SECRET, ID, Long.toString(TIMESTAMP),
            signature, body, SENT_AT.plusSeconds(2))).isTrue();
    }

    @Test
    @DisplayName("a correctly signed delivery older than five minutes is refused as a replay")
    void anOldDeliveryIsRefused() {
        byte[] body = "{\"event\":\"sync.completed\"}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.sign(SECRET, ID, TIMESTAMP, body);

        assertThat(StandardWebhooksReceiver.accepts(SECRET, ID, Long.toString(TIMESTAMP),
            signature, body, SENT_AT.plusSeconds(5 * 60 + 1)))
            .as("the timestamp is signed, so a captured request has a five-minute life")
            .isFalse();
    }

    @Test
    @DisplayName("a replay cannot refresh the timestamp: the old signature no longer matches")
    void theTimestampIsCovered() {
        byte[] body = "{\"event\":\"sync.completed\"}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.sign(SECRET, ID, TIMESTAMP, body);
        Instant later = SENT_AT.plusSeconds(3600);

        assertThat(StandardWebhooksReceiver.accepts(SECRET, ID,
            Long.toString(later.getEpochSecond()), signature, body, later)).isFalse();
    }

    @Test
    @DisplayName("changing the delivery id invalidates the signature")
    void theDeliveryIdIsCovered() {
        byte[] body = "{\"event\":\"sync.completed\"}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.sign(SECRET, ID, TIMESTAMP, body);

        assertThat(StandardWebhooksReceiver.accepts(SECRET,
            "0b6d1f4e-2c1a-4e6b-8f1d-5a7c9e3b2d40", Long.toString(TIMESTAMP), signature, body,
            SENT_AT)).isFalse();
    }

    @Test
    @DisplayName("a single changed byte of the body invalidates the signature")
    void thePayloadIsCovered() {
        byte[] one = "{\"amount\":\"12.34\"}".getBytes(StandardCharsets.UTF_8);
        byte[] two = "{\"amount\":\"12.35\"}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.sign(SECRET, ID, TIMESTAMP, one);

        assertThat(StandardWebhooksReceiver.accepts(SECRET, ID, Long.toString(TIMESTAMP),
            signature, two, SENT_AT)).isFalse();
    }

    @Test
    @DisplayName("a different secret produces a different signature over the same delivery")
    void theSecretIsCovered() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSignature.sign(SECRET, ID, TIMESTAMP, body))
            .isNotEqualTo(WebhookSignature.sign("another-long-enough-secret", ID, TIMESTAMP,
                body));
    }

    @Test
    @DisplayName("signing is deterministic for the same id, timestamp and bytes")
    void signingIsDeterministic() {
        byte[] body = "{\"event\":\"sync.completed\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSignature.sign(SECRET, ID, TIMESTAMP, body))
            .isEqualTo(WebhookSignature.sign(SECRET, ID, TIMESTAMP, body));
    }

    @Test
    @DisplayName("an empty body still signs: v1, plus a 32-byte HMAC in base64")
    void anEmptyBodySigns() {
        assertThat(WebhookSignature.sign(SECRET, ID, TIMESTAMP, new byte[0]))
            .startsWith("v1,").hasSize(3 + 44);
    }
}
