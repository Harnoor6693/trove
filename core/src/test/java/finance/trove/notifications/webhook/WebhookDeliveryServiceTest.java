package finance.trove.notifications.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import finance.trove.app.HttpClientConfig;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.ResolvedTarget;
import finance.trove.notifications.NotificationProperties;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The retry loop's own behaviour, with no socket anywhere: what it claims, what it skips, and what
 * it does when the process is asked to stop.
 *
 * <p>The network half — the policy re-check, the pinned client, the recorded outcome — is
 * {@code WebhookDeliveryIT}'s, because it needs a real resolver and a real table.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookDeliveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");
    private static final String SECRET = "a-long-enough-webhook-secret";

    @Mock
    private WebhookSubscriptionRepository subscriptions;

    @Mock
    private WebhookDeliveryStore deliveries;

    @Mock
    private OutboundUrlPolicy policy;

    @Mock
    private HttpClientConfig.PinnedRestClientFactory clients;

    private final FieldEncryptionService encryption = new FieldEncryptionService(
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");

    private WebhookDeliveryService service;

    @BeforeEach
    void setUp() {
        service = new WebhookDeliveryService(subscriptions, deliveries, policy, clients,
            encryption,
            new NotificationProperties(null, null, null, null, 20, null),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private WebhookSubscription enabledSubscription(UUID id, List<String> eventTypes) {
        WebhookSubscription subscription = new WebhookSubscription(UUID.randomUUID(),
            "https://192.0.2.1/hook", "v1:ciphertext", eventTypes);
        ReflectionTestUtils.setField(subscription, "id", id);
        return subscription;
    }

    private static WebhookDeliveryLog queued(UUID subscriptionId) {
        WebhookDeliveryLog row =
            new WebhookDeliveryLog(subscriptionId, "sync.completed", "{}", NOW);
        ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        return row;
    }

    @Test
    @DisplayName("A receiver's response body is never copied into the delivery row")
    void anAnsweredCallIsDescribedByItsStatusAndNothingElse() {
        // The commonest failure of all: the receiver answers 4xx or 5xx. Spring builds
        // RestClientResponseException's message as `<status> <statusText>: "<response body>"`, so
        // persisting getMessage() copied an arbitrary third party's error page — HTML, a stack
        // trace, whatever it chose to echo — verbatim into a column of this database, which is
        // exactly what describe()'s own javadoc promised it did not do. The row is diagnostics
        // about Trove, not a mirror of somebody else's server.
        String body = "<html><body>Stack trace: com.acme.Boom at Secret.java:42</body></html>";
        RestClientResponseException answered = new RestClientResponseException(
            "500 Internal Server Error: \"" + body + "\"", 500, "Internal Server Error",
            HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        String described = WebhookDeliveryService.describe(answered);

        assertThat(described).isEqualTo("The receiver answered 500 Internal Server Error");
        assertThat(described)
            .as("nothing the receiver chose to send is retained")
            .doesNotContain("Stack trace", "com.acme.Boom", "<html>");
        assertThat(WebhookDeliveryService.statusCodeOf(answered)).isEqualTo(500);
    }

    @Test
    @DisplayName("A transport failure keeps the client's own words, which are the diagnosis")
    void aTransportFailureKeepsItsMessage() {
        // Connect and read timeouts, DNS, TLS: here getMessage() is the HTTP client's own text
        // about this server's own call, and it is the whole of what an operator needs.
        String described = WebhookDeliveryService.describe(
            new java.net.SocketTimeoutException("connect timed out"));

        assertThat(described).isEqualTo("connect timed out");
        assertThat(WebhookDeliveryService.statusCodeOf(
            new java.net.SocketTimeoutException("connect timed out")))
            .as("a call that never got an answer has no status to record")
            .isNull();
    }

    @Test
    @DisplayName("A failure with no message at all is still described, by its type")
    void anEmptyMessageFallsBackToTheType() {
        assertThat(WebhookDeliveryService.describe(new IllegalStateException()))
            .as("a FAILED row always says something: an empty error_message is the shape of the "
                + "silent failure this step exists to remove")
            .isEqualTo("IllegalStateException");
    }

    @Test
    @DisplayName("A very long transport message is truncated to fit the column")
    void aLongMessageIsTruncated() {
        assertThat(WebhookDeliveryService.describe(
            new IllegalStateException("x".repeat(900)))).hasSize(500);
    }

    @Test
    @DisplayName("scheduleDelivery queues nothing for an unknown or disabled subscription")
    void nothingIsQueuedForAnAbsentSubscription() {
        UUID id = UUID.randomUUID();
        when(subscriptions.findSubscription(id)).thenReturn(Optional.empty());

        assertThat(service.scheduleDelivery(id, "sync.completed", "{}")).isEmpty();
        verify(deliveries, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    @DisplayName("scheduleDelivery queues nothing for an event the subscription did not ask for")
    void theEventFilterIsApplied() {
        UUID id = UUID.randomUUID();
        when(subscriptions.findSubscription(id))
            .thenReturn(Optional.of(enabledSubscription(id, List.of("receipt.processed"))));

        assertThat(service.scheduleDelivery(id, "sync.completed", "{}")).isEmpty();
        verify(deliveries, never()).enqueue(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A subscription that named no events gets everything, queued due now")
    void anEmptyFilterMeansEverything() {
        UUID id = UUID.randomUUID();
        WebhookDeliveryLog row = queued(id);
        when(subscriptions.findSubscription(id))
            .thenReturn(Optional.of(enabledSubscription(id, List.of())));
        when(deliveries.enqueue(id, "sync.completed", "{}", NOW)).thenReturn(row);

        assertThat(service.scheduleDelivery(id, "sync.completed", "{}"))
            .contains(row.getId());
        verify(deliveries).enqueue(id, "sync.completed", "{}", NOW);
    }

    @Test
    @DisplayName("A pass attempts every row the claim handed it")
    void everyClaimedRowIsAttempted() {
        UUID subscriptionId = UUID.randomUUID();
        when(deliveries.claimDue(NOW, 20))
            .thenReturn(List.of(queued(subscriptionId), queued(subscriptionId)));
        when(subscriptions.findSubscription(subscriptionId)).thenReturn(Optional.empty());

        assertThat(service.deliverDue()).isEqualTo(2);
        verify(deliveries, org.mockito.Mockito.times(2)).persistOutcome(any());
    }

    @Test
    @DisplayName("An empty queue does nothing at all")
    void anEmptyQueueIsANoOp() {
        when(deliveries.claimDue(any(), anyInt())).thenReturn(List.of());

        assertThat(service.deliverDue()).isZero();
        verify(deliveries, never()).persistOutcome(any());
    }

    @Test
    @DisplayName("An interrupted pass stops between rows and leaves the rest IN_FLIGHT")
    void interruptionStopsBetweenRows() throws Exception {
        // §5 item 13, graceful shutdown: a deploy in the middle of a batch of slow receivers has
        // to be able to stop. Stopping BETWEEN rows rather than inside one is what keeps a
        // half-finished POST from being retried by the instance coming up.
        UUID subscriptionId = UUID.randomUUID();
        when(deliveries.claimDue(NOW, 20))
            .thenReturn(List.of(queued(subscriptionId), queued(subscriptionId)));
        when(subscriptions.findSubscription(subscriptionId)).thenReturn(Optional.empty());

        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            assertThat(service.deliverDue())
                .as("the interrupt is seen before the first row, so nothing is attempted")
                .isZero();
        });
        worker.start();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        verify(deliveries, never()).persistOutcome(any());
    }

    @Test
    @DisplayName("A delivery whose subscription vanished is abandoned, not retried")
    void aVanishedSubscriptionIsAbandoned() {
        UUID subscriptionId = UUID.randomUUID();
        WebhookDeliveryLog row = queued(subscriptionId);
        when(subscriptions.findSubscription(subscriptionId)).thenReturn(Optional.empty());

        service.attempt(row);

        assertThat(row.getStatus()).isEqualTo(WebhookDeliveryLog.Status.EXHAUSTED);
        assertThat(row.getErrorMessage()).contains("deleted or disabled");
        assertThat(row.getAttemptCount())
            .as("abandoning spends no attempt: every retry would meet the same condition")
            .isZero();
        verify(deliveries).persistOutcome(row);
    }

    @Test
    @DisplayName("The webhook timeouts are the ten seconds §2.8 names, not the global defaults")
    void theTimeoutsAreTheWebhookOnes() {
        assertThat(WebhookDeliveryService.CONNECT_TIMEOUT)
            .isEqualTo(java.time.Duration.ofSeconds(10));
        assertThat(WebhookDeliveryService.READ_TIMEOUT)
            .isEqualTo(java.time.Duration.ofSeconds(10));
        assertThat(SECRET).isNotBlank();
    }
    @Test
    @DisplayName("B3-03: a delivery is signed Standard Webhooks style, over its id and a timestamp")
    void aDeliveryIsSignedOverItsIdAndATimestamp() throws Exception {
        UUID subscriptionId = UUID.randomUUID();
        WebhookSubscription subscription = new WebhookSubscription(UUID.randomUUID(),
            "https://hooks.example.test/hook", encryption.encrypt(SECRET), List.of());
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        when(subscriptions.findSubscription(subscriptionId)).thenReturn(Optional.of(subscription));
        ResolvedTarget target = new ResolvedTarget("hooks.example.test",
            InetAddress.getByName("192.0.2.1"), 443, URI.create("https://hooks.example.test/hook"),
            List.of(InetAddress.getByName("192.0.2.1")));
        when(policy.check("https://hooks.example.test/hook")).thenReturn(target);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer receiver = MockRestServiceServer.bindTo(builder).build();
        when(clients.builderFor(eq(target), any(), any())).thenReturn(builder);
        AtomicReference<HttpHeaders> sent = new AtomicReference<>();
        receiver.expect(requestTo("https://hooks.example.test/hook"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(request -> sent.set(request.getHeaders()))
            .andRespond(withSuccess());
        String payload = "{\"event\":\"budget.threshold_crossed\"}";
        WebhookDeliveryLog row = new WebhookDeliveryLog(subscriptionId, "budget.threshold_crossed",
            payload, NOW);
        ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);

        service.attempt(row);

        receiver.verify();
        HttpHeaders headers = sent.get();
        String id = headers.getFirst("webhook-id");
        String timestamp = headers.getFirst("webhook-timestamp");
        String signature = headers.getFirst("webhook-signature");
        assertThat(id).as("the delivery id, stable across retries").isEqualTo(row.getId().toString());
        assertThat(timestamp).isEqualTo(Long.toString(NOW.getEpochSecond()));
        assertThat(headers.getFirst("X-Trove-Event")).isEqualTo("budget.threshold_crossed");
        assertThat(StandardWebhooksReceiver.accepts(SECRET, id, timestamp, signature, body,
            NOW.plusSeconds(30)))
            .as("a receiver following the published scheme accepts a fresh delivery")
            .isTrue();
        assertThat(StandardWebhooksReceiver.accepts(SECRET, id, timestamp, signature, body,
            NOW.plus(StandardWebhooksReceiver.TOLERANCE).plusSeconds(1)))
            .as("and refuses the same captured request replayed after the five-minute window")
            .isFalse();
        assertThat(StandardWebhooksReceiver.accepts(SECRET, UUID.randomUUID().toString(),
            timestamp, signature, body, NOW))
            .as("the id is signed, so it cannot be swapped to slip a replay past dedup")
            .isFalse();
        assertThat(row.getStatus()).isEqualTo(WebhookDeliveryLog.Status.DELIVERED);
    }
}
