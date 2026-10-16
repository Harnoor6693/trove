package finance.trove.notifications.webhook;

import finance.trove.app.HttpClientConfig;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.OutboundUrlPolicyException;
import finance.trove.common.net.ResolvedTarget;
import finance.trove.common.time.Instants;
import finance.trove.notifications.NotificationProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Delivering one member's events to one member's URL — the security half of collapse step 11
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D3; inventory defect D-N2; §9 risk 9).
 *
 * <h2>The policy runs again on every attempt, and the address it returns is the address we dial</h2>
 * The source validated {@code target_url} once, with a Bean Validation annotation that only fired
 * on {@code @Valid} at creation time, and then handed the stored STRING to an HTTP client
 * (WebhookDeliveryService.java:49-75). That is two independent DNS lookups with an unbounded gap
 * between them: a name that answers a public address while the subscription is being saved and
 * {@code 169.254.169.254} an hour later is a working SSRF against the host's own credentials, and
 * every authenticated member could set one up.
 *
 * <p>Both halves are fixed here. {@link OutboundUrlPolicy#check} runs before EVERY attempt, so a
 * name that has started resolving somewhere it should not is refused at delivery time rather than
 * only at creation time; and the {@link ResolvedTarget} it returns is handed to
 * {@code PinnedRestClientFactory}, whose {@code DnsResolver} only ever returns the address the
 * policy approved. Check and connect share one resolution.
 *
 * <p><strong>There is no {@code webhookRestClientBuilder} bean</strong>, whatever §5 item 5 says:
 * the merged {@code HttpClientConfig} exposes {@code PinnedRestClientFactory.builderFor(target,
 * connect, read)} and that overload — with the 10 s / 10 s §2.8 names — is what this uses. The
 * plan's name for it ({@code webhookRestClientBuilder}) is a label, not a bean.
 *
 * <h2>deliverDue() uses two short transactions, and neither spans the HTTP call</h2>
 * {@link WebhookDeliveryStore#claimDue} takes rows and commits {@code IN_FLIGHT};
 * {@link #attempt} calls out with no transaction at all; {@link WebhookDeliveryStore#persistOutcome}
 * writes the result. The source wrapped the whole pass in one, pinning a database connection for the
 * life of somebody else's slow endpoint.
 *
 * <h2>Who publishes into the queue</h2>
 * {@code WebhookEventListener} turns each domain event into {@link #scheduleDelivery} calls; this
 * class is the seam it calls.
 */
@Service
public class WebhookDeliveryService {

    /** §2.8's timeouts for a webhook: ten seconds to connect, ten to read. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** As above. A receiver slower than this is a receiver that gets a retry. */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOG = LoggerFactory.getLogger(WebhookDeliveryService.class);

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryStore deliveries;
    private final OutboundUrlPolicy policy;
    private final HttpClientConfig.PinnedRestClientFactory clients;
    private final FieldEncryptionService encryption;
    private final NotificationProperties properties;
    private final Clock clock;

    /** The constructor Spring uses; the class has two, so the container needs to be told. */
    @Autowired
    public WebhookDeliveryService(WebhookSubscriptionRepository subscriptions,
                                  WebhookDeliveryStore deliveries, OutboundUrlPolicy policy,
                                  HttpClientConfig.PinnedRestClientFactory clients,
                                  FieldEncryptionService encryption,
                                  NotificationProperties properties) {
        this(subscriptions, deliveries, policy, clients, encryption, properties,
            Instants.CLOCK);
    }

    /** Test seam: a fixed clock makes the backoff schedule assertable. */
    public WebhookDeliveryService(WebhookSubscriptionRepository subscriptions,
                                  WebhookDeliveryStore deliveries, OutboundUrlPolicy policy,
                                  HttpClientConfig.PinnedRestClientFactory clients,
                                  FieldEncryptionService encryption,
                                  NotificationProperties properties, Clock clock) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.policy = policy;
        this.clients = clients;
        this.encryption = encryption;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Queues one event for one subscription — <strong>the seam {@code WebhookEventListener} calls</strong>.
     *
     * <p>Signature: {@code Optional<UUID> scheduleDelivery(UUID subscriptionId, String
     * eventType, String payload)}, returning the delivery row's id, or empty when the subscription
     * does not exist, is disabled, or does not want this event type. It is safe to call from a
     * {@code @TransactionalEventListener(AFTER_COMMIT)}: the write is
     * {@code REQUIRES_NEW}, so it does not need a transaction to join.
     *
     * <p>The row is queued due NOW, so the next 30-second retry pass picks it up. There is no
     * inline attempt: doing the HTTP call on the publishing thread would put somebody else's
     * endpoint's latency inside the request that caused the event.
     */
    public Optional<UUID> scheduleDelivery(UUID subscriptionId, String eventType, String payload) {
        Optional<WebhookSubscription> found = subscriptions.findSubscription(subscriptionId);
        if (found.isEmpty() || !found.get().isEnabled()) {
            LOG.debug("webhook subscription {} is absent or disabled; nothing queued",
                subscriptionId);
            return Optional.empty();
        }
        if (!found.get().wants(eventType)) {
            return Optional.empty();
        }
        return Optional.of(
            deliveries.enqueue(subscriptionId, eventType, payload, clock.instant()).getId());
    }

    /**
     * One pass of the retry loop: claim, call, persist.
     *
     * <p>Interruption is checked between rows (§5 item 13, graceful shutdown): a deploy during a
     * batch of slow receivers must be able to stop. A row already claimed stays {@code IN_FLIGHT}
     * until it is stale, and then a later pass claims it again (B3-01).
     *
     * @return how many deliveries this pass attempted
     */
    public int deliverDue() {
        List<WebhookDeliveryLog> claimed =
            deliveries.claimDue(clock.instant(), properties.webhookBatchSize());
        int attempted = 0;
        for (WebhookDeliveryLog row : claimed) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("webhook retry interrupted after {} deliveries; {} rows stay IN_FLIGHT "
                    + "until a later pass reclaims them",
                    attempted, claimed.size() - attempted);
                break;
            }
            attempt(row);
            attempted++;
        }
        return attempted;
    }

    /**
     * Attempts one delivery, outside every transaction, and persists the outcome in its own.
     *
     * <p>Package-private so that {@code WebhookDeliveryIT} can drive one row without waiting for a
     * scheduler tick.
     */
    void attempt(WebhookDeliveryLog row) {
        Optional<WebhookSubscription> found =
            subscriptions.findSubscription(row.getSubscriptionId());
        if (found.isEmpty() || !found.get().isEnabled()) {
            row.abandon("The subscription was deleted or disabled before this delivery ran.",
                clock.instant());
            deliveries.persistOutcome(row);
            return;
        }
        WebhookSubscription subscription = found.get();

        ResolvedTarget target;
        try {
            target = policy.check(subscription.getTargetUrl());
        } catch (OutboundUrlPolicyException e) {
            // swallow-ok: this is the D-N2 defence doing its job, not a lost failure. The refusal
            // is written to the row below and the delivery is abandoned rather than retried — a
            // URL the policy refuses now will be refused on every retry, and retrying it would
            // turn one refusal into five.
            LOG.warn("webhook delivery {} refused by the egress policy: {}", row.getId(),
                e.getMessage());
            row.abandon("The target URL is no longer allowed: " + e.getMessage(), clock.instant());
            deliveries.persistOutcome(row);
            return;
        }

        byte[] body = row.getPayload().getBytes(StandardCharsets.UTF_8);
        String deliveryId = row.getId().toString();
        long timestamp = clock.instant().getEpochSecond();
        String signature = WebhookSignature.sign(encryption.decrypt(subscription
            .getSecretEncrypted()), deliveryId, timestamp, body);

        try {
            RestClient client = clients.builderFor(target, CONNECT_TIMEOUT, READ_TIMEOUT).build();
            ResponseEntity<Void> response = client.post()
                .uri(target.uri())
                .contentType(MediaType.APPLICATION_JSON)
                .header(WebhookSignature.ID_HEADER, deliveryId)
                .header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(timestamp))
                .header(WebhookSignature.SIGNATURE_HEADER, signature)
                .header(WebhookSignature.EVENT_HEADER, row.getEventType())
                .body(body)
                .retrieve()
                .toBodilessEntity();
            row.delivered(response.getStatusCode().value(), clock.instant());
        } catch (Exception e) {
            // swallow-ok: an unreachable or refusing receiver is an ordinary outcome, and it is
            // RECORDED on the row below with its reason rather than dropped. Rethrowing would
            // abort the whole retry pass over one bad endpoint.
            row.attemptFailed(statusCodeOf(e), describe(e), clock.instant());
            LOG.debug("webhook delivery {} failed on attempt {}", row.getId(),
                row.getAttemptCount());
        }
        deliveries.persistOutcome(row);
    }

    /** The HTTP status a client exception carries, or null when the call never got one. */
    static Integer statusCodeOf(Exception e) {
        return e instanceof RestClientResponseException http ? http.getStatusCode().value() : null;
    }

    /**
     * A reason short enough for a column and specific enough to act on.
     *
     * <p><strong>Never the response body.</strong> That sentence used to be a comment over
     * {@code e.getMessage()}, which does not keep it: for the commonest failure of all — the
     * receiver answering 4xx or 5xx — Spring builds {@code RestClientResponseException}'s message
     * as {@code "<status> <statusText>: \"<the response body>\""}, so an arbitrary third party's
     * error page was being copied verbatim into a column of this database and would be rendered by
     * whatever delivery-history screen is built next. A receiver's HTML, its stack trace, or
     * whatever it chose to echo back is not diagnostics about Trove.
     *
     * <p>So an answered call is described by its STATUS, which is the only part of the response
     * this server chose to look at, and {@code getMessage()} is kept only for the transport
     * failures — connect and read timeouts, DNS, TLS — where the message is the client's own words
     * and is the whole diagnosis.
     *
     * <p>Package-private rather than private so that {@code WebhookDeliveryServiceTest} can hand
     * it the exact exception Spring builds for an answering receiver — which is the only way to
     * assert that the body does not survive into the column, since no legal delivery target in
     * this suite can answer at all.
     */
    static String describe(Exception e) {
        if (e instanceof RestClientResponseException http) {
            return "The receiver answered " + http.getStatusCode().value() + " "
                + http.getStatusText();
        }
        String message = e.getMessage();
        String text = message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
        return text.length() > 500 ? text.substring(0, 500) : text;
    }
}
