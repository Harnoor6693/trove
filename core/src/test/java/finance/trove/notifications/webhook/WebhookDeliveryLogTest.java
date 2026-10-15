package finance.trove.notifications.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The retry state machine: exponential backoff, five attempts, then EXHAUSTED. */
class WebhookDeliveryLogTest {

    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");

    private static WebhookDeliveryLog queued() {
        return new WebhookDeliveryLog(UUID.randomUUID(), "budget.threshold_crossed", "{}", NOW);
    }

    @Test
    @DisplayName("a new row is PENDING, due now, with no attempts")
    void aNewRowIsPending() {
        WebhookDeliveryLog row = queued();

        assertThat(row.getStatus()).isEqualTo(WebhookDeliveryLog.Status.PENDING);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(row.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("backoff is 2^attempt seconds, the source's rule kept")
    void backoffDoubles() {
        assertThat(WebhookDeliveryLog.backoff(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(WebhookDeliveryLog.backoff(2)).isEqualTo(Duration.ofSeconds(4));
        assertThat(WebhookDeliveryLog.backoff(3)).isEqualTo(Duration.ofSeconds(8));
        assertThat(WebhookDeliveryLog.backoff(4)).isEqualTo(Duration.ofSeconds(16));
    }

    @Test
    @DisplayName("a failed attempt schedules the next one and records the reason")
    void aFailedAttemptSchedulesTheNext() {
        WebhookDeliveryLog row = queued();

        row.attemptFailed(503, "Service Unavailable", NOW);

        assertThat(row.getStatus()).isEqualTo(WebhookDeliveryLog.Status.FAILED);
        assertThat(row.getAttemptCount()).isEqualTo(1);
        assertThat(row.getLastStatusCode()).isEqualTo(503);
        assertThat(row.getErrorMessage()).isEqualTo("Service Unavailable");
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    @DisplayName("the fifth failure is EXHAUSTED and schedules nothing")
    void fiveFailuresExhaust() {
        WebhookDeliveryLog row = queued();

        for (int attempt = 1; attempt <= WebhookDeliveryLog.MAX_ATTEMPTS; attempt++) {
            row.attemptFailed(500, "boom", NOW);
        }

        assertThat(row.getStatus()).isEqualTo(WebhookDeliveryLog.Status.EXHAUSTED);
        assertThat(row.getAttemptCount()).isEqualTo(WebhookDeliveryLog.MAX_ATTEMPTS);
        assertThat(row.getNextAttemptAt())
            .as("a null next attempt is what keeps the due index from ever returning it again")
            .isNull();
        assertThat(row.getErrorMessage()).isEqualTo("boom");
    }

    @Test
    @DisplayName("a 2xx clears the schedule and the error")
    void deliveredClearsEverything() {
        WebhookDeliveryLog row = queued();
        row.attemptFailed(503, "Service Unavailable", NOW);

        row.delivered(204, NOW.plusSeconds(2));

        assertThat(row.getStatus()).isEqualTo(WebhookDeliveryLog.Status.DELIVERED);
        assertThat(row.getAttemptCount()).isEqualTo(2);
        assertThat(row.getLastStatusCode()).isEqualTo(204);
        assertThat(row.getErrorMessage()).isNull();
        assertThat(row.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("abandoning spends no attempt but says why")
    void abandonRecordsTheReason() {
        // Used when the subscription is gone, and when the egress policy refuses the URL: both
        // are conditions every retry would meet again, so spending five attempts on them would
        // turn one refusal into five.
        WebhookDeliveryLog row = queued();

        row.abandon("The target URL is no longer allowed: resolves to a private address", NOW);

        assertThat(row.getStatus()).isEqualTo(WebhookDeliveryLog.Status.EXHAUSTED);
        assertThat(row.getAttemptCount()).isZero();
        assertThat(row.getNextAttemptAt()).isNull();
        assertThat(row.getErrorMessage()).contains("no longer allowed");
    }
}
