package finance.trove.notifications.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The signature a receiver verifies — over the exact bytes that go on the wire. */
class WebhookSignatureTest {

    private static final String SECRET = "a-long-enough-webhook-secret";

    @Test
    @DisplayName("the signature is HMAC-SHA256 over the exact bytes, hex, with an sha256= prefix")
    void theSignatureIsOverTheExactBytes() throws Exception {
        String payload = "{\"event\":\"budget.threshold_crossed\",\"amount\":\"12.34\"}";
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(bytes));

        assertThat(WebhookSignature.sign(SECRET, bytes)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a single changed byte changes the signature")
    void thePayloadIsCovered() {
        byte[] one = "{\"amount\":\"12.34\"}".getBytes(StandardCharsets.UTF_8);
        byte[] two = "{\"amount\":\"12.35\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSignature.sign(SECRET, one))
            .isNotEqualTo(WebhookSignature.sign(SECRET, two));
    }

    @Test
    @DisplayName("a different secret produces a different signature over the same bytes")
    void theSecretIsCovered() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSignature.sign(SECRET, body))
            .isNotEqualTo(WebhookSignature.sign("another-long-enough-secret", body));
    }

    @Test
    @DisplayName("signing is deterministic, so a retry carries the same signature")
    void signingIsDeterministic() {
        // A receiver that deduplicates on the signature must see the same one for a redelivery
        // of the same event; the delivery id header is stable for the same reason.
        byte[] body = "{\"event\":\"sync.completed\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSignature.sign(SECRET, body))
            .isEqualTo(WebhookSignature.sign(SECRET, body));
    }

    @Test
    @DisplayName("an empty body still signs")
    void anEmptyBodySigns() {
        assertThat(WebhookSignature.sign(SECRET, new byte[0])).startsWith("sha256=").hasSize(71);
    }
}
