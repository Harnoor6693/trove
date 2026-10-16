package finance.trove.notifications.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.HttpClientConfig;
import finance.trove.app.support.Json;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.net.EgressProperties;
import finance.trove.common.net.HostResolver;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.notifications.NotificationProperties;
import finance.trove.notifications.NotificationSupport;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Webhook delivery: inventory defect <strong>D-N2</strong>, and the three-transaction
 * {@code deliver()} shape of §6 D3.
 *
 * <h2>D-N2, in one sentence</h2>
 * The source validated {@code target_url} once, with a Bean Validation annotation that only ran on
 * {@code @Valid} at creation time, and then handed the stored STRING to an HTTP client
 * (WebhookDeliveryService.java:49-75). Validation and connection were two independent DNS lookups
 * with an unbounded gap between them, so a name that answered a public address while the
 * subscription was being saved and {@code 169.254.169.254} an hour later was a working SSRF
 * against the host's own credentials — available to every authenticated member.
 * {@link #aHostThatRebindsToAPrivateAddressIsRefusedAtDeliveryTime()} is that exact sequence, and
 * it is why {@code OutboundUrlPolicy} takes a {@link HostResolver} in its second constructor.
 *
 * <h2>Every target in this file is inside 2d00::/8</h2>
 * Global unicast that IANA has not delegated to any regional registry (the IANA IPv6 Global
 * Unicast Address Assignments registry lists {@code 2d00::/8} as RESERVED), so nobody announces
 * it, nothing routes to it and nobody answers on it. It is public as far as
 * {@code OutboundUrlPolicy} is concerned: RFC 4291 §2.4 says to treat unallocated space inside
 * {@code 2000::/3} as global unicast, so the policy allows it, which is exactly what a delivery
 * test needs. RFC 5737's TEST-NET-1 used to play this part; the IANA IPv4 special-purpose
 * registry marks it not globally reachable and the policy now refuses it. An
 * earlier version of this file targeted a real, routable host belonging to somebody else, so
 * {@code mvn verify} made unsolicited HTTPS requests to a third party on every run. No test here
 * may name a dialable address, and {@link #everyAddressInThisFileIsReserved()} reads this file
 * back to keep it that way.
 */
class WebhookDeliveryIT extends NotificationSupport {

    /** A name whose answer this test controls. It never reaches a real resolver. */
    private static final String REBINDING_HOST = "rebind.trove.test";

    private static final String SECRET = "a-long-enough-webhook-secret";

    @Autowired
    private WebhookSubscriptionRepository subscriptions;

    @Autowired
    private WebhookDeliveryStore store;

    @Autowired
    private HttpClientConfig.PinnedRestClientFactory clients;

    @Autowired
    private FieldEncryptionService encryption;

    @Autowired
    private WebhookDeliveryService realDeliveries;

    private Session member;

    @BeforeEach
    void signIn() {
        member = inviteAndRegister(loginBootstrapAdmin(), "webhook-delivery");
    }

    /** A resolver that answers a public address once, then a private one — the rebind. */
    private static final class RebindingResolver implements HostResolver {

        private final AtomicBoolean firstAnswerUsed = new AtomicBoolean();

        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            if (!REBINDING_HOST.equals(host)) {
                return InetAddress.getAllByName(host);
            }
            return firstAnswerUsed.compareAndSet(false, true)
                ? new InetAddress[] {InetAddress.getByName("2d00::9")}
                : new InetAddress[] {InetAddress.getByName("169.254.169.254")};
        }
    }

    private WebhookDeliveryService deliveriesUsing(OutboundUrlPolicy policy) {
        return new WebhookDeliveryService(subscriptions, store, policy, clients, encryption,
            new NotificationProperties(null, null, null, null, null, null));
    }

    private UUID subscriptionFor(String targetUrl) {
        WebhookSubscription subscription = subscriptions.save(new WebhookSubscription(
            member.userId(), targetUrl, encryption.encrypt(SECRET), List.of()));
        return subscription.getId();
    }

    private Map<String, Object> deliveryRow(UUID id) {
        return jdbcTemplate.queryForMap(
            "SELECT status, attempt_count, next_attempt_at, error_message, last_status_code "
                + "FROM webhook_delivery_log WHERE id = ?", id);
    }

    @Test
    @DisplayName("D-N2: a host that resolved public at creation and private at delivery is refused")
    void aHostThatRebindsToAPrivateAddressIsRefusedAtDeliveryTime() {
        RebindingResolver resolver = new RebindingResolver();
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new EgressProperties(false), resolver);

        // Creation time: the name answers a public address and the subscription is accepted.
        policy.check("https://" + REBINDING_HOST + "/hook");
        UUID subscriptionId = subscriptionFor("https://" + REBINDING_HOST + "/hook");
        WebhookDeliveryService deliveries = deliveriesUsing(policy);
        UUID deliveryId = deliveries
            .scheduleDelivery(subscriptionId, "sync.completed", "{\"ok\":true}")
            .orElseThrow();

        // Delivery time: the same name now answers the cloud metadata address.
        deliveries.attempt(store.find(deliveryId));

        Map<String, Object> row = deliveryRow(deliveryId);
        assertThat(row.get("status"))
            .as("the policy runs AGAIN before every attempt; the source ran it once, at save "
                + "time, and connected to whatever the second lookup returned")
            .isEqualTo("EXHAUSTED");
        assertThat((String) row.get("error_message"))
            .contains("no longer allowed")
            .doesNotContain("169.254.169.254");
        assertThat(row.get("attempt_count"))
            .as("a URL the policy refuses now will be refused on every retry, so this spends no "
                + "attempt and schedules none")
            .isEqualTo(0);
        assertThat(row.get("next_attempt_at")).isNull();
    }

    @Test
    @DisplayName("The claim is a transaction of its own: IN_FLIGHT is committed before any call")
    void theClaimCommitsBeforeTheCallStarts() {
        // §6 D3: the source wrapped the whole outbound HTTP call in one @Transactional, pinning a
        // database connection for up to twenty seconds per delivery. Reading the row back through
        // a separate statement after claimDue returns proves the claim committed on its own — if
        // the three steps shared one transaction, nothing outside it could see IN_FLIGHT.
        UUID subscriptionId = subscriptionFor("https://[2d00::1]/hook");
        UUID deliveryId = realDeliveries
            .scheduleDelivery(subscriptionId, "budget.threshold_crossed", "{}")
            .orElseThrow();
        assertThat(deliveryRow(deliveryId).get("status")).isEqualTo("PENDING");

        List<WebhookDeliveryLog> claimed = store.claimDue(Instant.now(), 50);

        assertThat(claimed).extracting(WebhookDeliveryLog::getId).contains(deliveryId);
        assertThat(deliveryRow(deliveryId).get("status"))
            .as("committed by the claim transaction, so a second pass — or a second instance — "
                + "cannot pick the same row up while its call is in flight")
            .isEqualTo("IN_FLIGHT");

        // And the third transaction: the outcome is written separately, after the call.
        realDeliveries.attempt(store.find(deliveryId));
        assertThat(deliveryRow(deliveryId).get("status")).isNotEqualTo("IN_FLIGHT");
    }

    @Test
    @DisplayName("A claimed row is not claimed twice")
    void anInFlightRowIsNotReclaimed() {
        UUID subscriptionId = subscriptionFor("https://[2d00::1]/hook");
        UUID deliveryId = realDeliveries.scheduleDelivery(subscriptionId, "sync.completed", "{}")
            .orElseThrow();

        store.claimDue(Instant.now(), 50);
        List<WebhookDeliveryLog> second = store.claimDue(Instant.now(), 50);

        assertThat(second).extracting(WebhookDeliveryLog::getId)
            .as("the due index covers PENDING and FAILED only; IN_FLIGHT is deliberately outside "
                + "it, so a row whose call is still running is never redelivered")
            .doesNotContain(deliveryId);
    }

    @Test
    @DisplayName("B3-01: a row a crash left IN_FLIGHT is claimed again once it is stale")
    void aStaleInFlightRowIsReclaimed() {
        UUID subscriptionId = subscriptionFor("https://[2d00::1]/hook");
        UUID deliveryId = realDeliveries.scheduleDelivery(subscriptionId, "sync.completed", "{}")
            .orElseThrow();
        store.claimDue(Instant.now(), 50);
        // The process died between the claim and the outcome: nothing will ever write this row
        // again, and the stale window is far past the twenty seconds one call can take.
        jdbcTemplate.update("UPDATE webhook_delivery_log SET updated_at = ? WHERE id = ?",
            java.sql.Timestamp.from(Instant.now()
                .minus(WebhookDeliveryStore.STALE_IN_FLIGHT).minusSeconds(60)),
            deliveryId);

        List<WebhookDeliveryLog> again = store.claimDue(Instant.now(), 50);

        assertThat(again).extracting(WebhookDeliveryLog::getId)
            .as("a crash or a deploy between claim and outcome must not drop the event for ever")
            .contains(deliveryId);
        assertThat(deliveryRow(deliveryId).get("status")).isEqualTo("IN_FLIGHT");
    }

    @Test
    @DisplayName("A deleted subscription abandons its queued delivery rather than retrying it")
    void aDeletedSubscriptionAbandonsTheDelivery() {
        UUID subscriptionId = subscriptionFor("https://[2d00::1]/hook");
        UUID deliveryId = realDeliveries.scheduleDelivery(subscriptionId, "sync.completed", "{}")
            .orElseThrow();
        WebhookDeliveryLog claimed = store.find(deliveryId);
        jdbcTemplate.update("DELETE FROM webhook_subscription WHERE id = ?", subscriptionId);

        realDeliveries.attempt(claimed);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM webhook_delivery_log WHERE id = ?", Integer.class, deliveryId))
            .as("the delivery row cascades away with its subscription, so there is nothing left "
                + "to persist an outcome onto")
            .isZero();
    }

    @Test
    @DisplayName("A disabled subscription queues nothing at all")
    void aDisabledSubscriptionQueuesNothing() {
        UUID subscriptionId = subscriptionFor("https://[2d00::1]/hook");
        jdbcTemplate.update("UPDATE webhook_subscription SET enabled = false WHERE id = ?",
            subscriptionId);

        Optional<UUID> queued =
            realDeliveries.scheduleDelivery(subscriptionId, "sync.completed", "{}");

        assertThat(queued).isEmpty();
    }

    @Test
    @DisplayName("scheduleDelivery honours the subscription's event filter")
    void theEventFilterIsHonoured() {
        // The seam collapse step 16 calls. It filters here rather than in the listener so that
        // every producer gets the same rule without repeating it.
        WebhookSubscription narrow = subscriptions.save(new WebhookSubscription(member.userId(),
            "https://[2d00::1]/hook", encryption.encrypt(SECRET),
            List.of("budget.threshold_crossed")));

        assertThat(realDeliveries.scheduleDelivery(narrow.getId(), "sync.completed", "{}"))
            .as("a subscription that named its events gets only those")
            .isEmpty();
        assertThat(realDeliveries.scheduleDelivery(narrow.getId(), "budget.threshold_crossed",
            "{}")).isPresent();
    }

    @Test
    @DisplayName("An unreachable receiver is FAILED with a reason and a scheduled retry")
    void anUnreachableReceiverIsRetried() {
        // The refusal here comes from the NETWORK rather than from the policy (2d00::/8 is
        // routed nowhere but is not on any block list), which is the case this asserts: an
        // unreachable receiver produces a RETRY rather than an abandonment. Worst case it costs
        // the ten-second connect timeout §2.8 names.
        UUID subscriptionId = subscriptionFor("https://[2d00::1]/hook");
        UUID deliveryId = realDeliveries.scheduleDelivery(subscriptionId, "sync.completed", "{}")
            .orElseThrow();

        realDeliveries.attempt(store.find(deliveryId));

        Map<String, Object> row = deliveryRow(deliveryId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat((String) row.get("error_message"))
            .as("a FAILED row always says why — the same rule notification_log holds")
            .isNotBlank();
        assertThat(row.get("next_attempt_at"))
            .as("2^1 seconds from now: the source's exponential backoff, kept")
            .isNotNull();
    }

    @Test
    @DisplayName("The signature covers the exact bytes the delivery would send")
    void theSignatureIsOverTheExactPayload() {
        // Asserted here as well as in WebhookSignatureTest because this is where the secret comes
        // back out of the database: a delivery signs with the DECRYPTED stored secret, so a
        // round-trip failure would produce signatures no receiver could verify and nothing else
        // would notice.
        String payload = "{\"event\":\"sync.completed\",\"accountId\":\"" + UUID.randomUUID()
            + "\"}";
        UUID subscriptionId = subscriptionFor("https://[2d00::1]/hook");
        String storedCipher = jdbcTemplate.queryForObject(
            "SELECT secret_encrypted FROM webhook_subscription WHERE id = ?", String.class,
            subscriptionId);

        byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String deliveryId = UUID.randomUUID().toString();
        long timestamp = Instant.now().getEpochSecond();

        String fromStore = WebhookSignature.sign(encryption.decrypt(storedCipher), deliveryId,
            timestamp, body);

        assertThat(StandardWebhooksReceiver.accepts(SECRET, deliveryId,
            Long.toString(timestamp), fromStore, body, Instant.now()))
            .as("a receiver holding the secret the member typed verifies what the stored, "
                + "encrypted copy signs")
            .isTrue();
    }

    @Test
    @DisplayName("No address in this file belongs to anybody: they are all in 2d00::/8")
    void everyAddressInThisFileIsReserved() throws Exception {
        // The rule the class javadoc states, enforced rather than asked for. Read from the source
        // tree the way SourceConventionTest resolves it — from the compiled output's location, so
        // it does not depend on the working directory a runner happens to use.
        Path classes = Paths.get(finance.trove.app.TroveApplication.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI());
        Path source = classes.getParent().getParent().resolve("src/test/java")
            .resolve(getClass().getName().replace('.', '/') + ".java");
        String text = Files.readString(source);

        List<String> dialable = Pattern.compile("\\b(\\d{1,3}(?:\\.\\d{1,3}){3})\\b")
            .matcher(text).results().map(match -> match.group(1)).distinct()
            // The cloud metadata address the rebind test REFUSES. Naming it is the point of that
            // test; nothing ever connects to it, because the policy stops the delivery first.
            .filter(address -> !"169.254.169.254".equals(address))
            .toList();
        List<String> ipv6Targets = Pattern
            .compile("(?:\\[|getByName\\(\")([0-9a-fA-F:]*:[0-9a-fA-F:]*)").matcher(text)
            .results().map(match -> match.group(1)).distinct()
            .filter(address -> !address.startsWith("2d00:"))
            .toList();

        assertThat(dialable)
            .as("a test that dials an address somebody owns makes `mvn verify` send unsolicited "
                + "traffic to a third party; every target here is in undelegated 2d00::/8")
            .isEmpty();
        assertThat(ipv6Targets)
            .as("every IPv6 target here is in 2d00::/8, which IANA has delegated to nobody")
            .isEmpty();
    }

    @Test
    @DisplayName("A delivery for a subscription that no longer exists queues nothing")
    void anUnknownSubscriptionQueuesNothing() {
        assertThat(realDeliveries.scheduleDelivery(UUID.randomUUID(), "sync.completed", "{}"))
            .isEmpty();
    }

    @Test
    @DisplayName("The queued payload and event type are stored verbatim for the retry")
    void thePayloadIsStoredForRetry() {
        String payload = Json.object("event", "sync.completed", "accountId",
            UUID.randomUUID().toString());
        UUID subscriptionId = subscriptionFor("https://[2d00::1]/hook");

        UUID deliveryId = realDeliveries
            .scheduleDelivery(subscriptionId, "sync.completed", payload).orElseThrow();

        Map<String, Object> row = jdbcTemplate.queryForMap(
            "SELECT event_type, payload FROM webhook_delivery_log WHERE id = ?", deliveryId);
        assertThat(row.get("event_type")).isEqualTo("sync.completed");
        assertThat(row.get("payload"))
            .as("byte-for-byte: the signature is over what is sent, and a re-serialisation "
                + "between signing and sending would make every delivery look forged")
            .isEqualTo(payload);
    }
}
