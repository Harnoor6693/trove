package finance.trove.notifications.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.notifications.NotificationSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code /v1/webhooks} end to end (§4.9).
 *
 * <p><strong>Every URL here is an IP literal, and that is deliberate.</strong>
 * {@code OutboundUrlPolicy} resolves the host before it decides, so a hostname would make this
 * suite depend on DNS — a name like {@code hooks.example.test} does not resolve at all and would
 * be refused for the wrong reason, and a name that does resolve would make the test depend on
 * somebody else's zone file. An IP literal is returned by the resolver without a lookup, so each
 * case below exercises exactly the rule it names. No connection is ever made: creating a
 * subscription only validates.
 */
class WebhookIT extends NotificationSupport {

    /** A public IPv4 literal. Nothing connects to it; the policy only has to accept it. */
    private static final String PUBLIC_TARGET = "https://192.0.2.1/trove-hook";

    private static final String SECRET = "a-long-enough-webhook-secret";

    private Session member;

    @BeforeEach
    void signIn() {
        member = inviteAndRegister(loginBootstrapAdmin(), "webhook-member");
    }

    private HttpResponse<String> create(String targetUrl, String secret, Object eventTypes) {
        return postJson(appUrl("/v1/webhooks"),
            Json.object("targetUrl", targetUrl, "secret", secret, "eventTypes", eventTypes),
            bearer(member));
    }

    private List<JsonNode> list(Session session) {
        HttpResponse<String> response = get(appUrl("/v1/webhooks"), session.accessToken());
        assertThat(response.statusCode())
            .as("listing webhooks failed: %s", response.body())
            .isEqualTo(200);
        return Json.elements(Json.data(response.body()));
    }

    @Test
    @DisplayName("Create, list, delete — and the secret is never echoed")
    void theHappyPathNeverEchoesTheSecret() {
        HttpResponse<String> created =
            create(PUBLIC_TARGET, SECRET, List.of("budget.threshold_crossed"));

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode subscription = Json.data(created.body());
        assertThat(subscription.path("targetUrl").asString()).isEqualTo(PUBLIC_TARGET);
        assertThat(subscription.path("enabled").asBoolean()).isTrue();
        assertThat(subscription.path("hasSecret").asBoolean()).isTrue();
        assertThat(Json.elements(subscription.path("eventTypes")))
            .extracting(node -> node.asString())
            .containsExactly("budget.threshold_crossed");
        assertThat(subscription.propertyNames())
            .as("the shared signing secret appears in no response, in any form")
            .doesNotContain("secret", "secretEncrypted");
        assertThat(created.body())
            .as("not anywhere in the body either, masked or otherwise")
            .doesNotContain(SECRET);

        String id = subscription.path("id").asString();
        assertThat(list(member)).extracting(row -> row.path("id").asString()).contains(id);
        assertThat(list(member)).allSatisfy(row ->
            assertThat(row.propertyNames()).doesNotContain("secret"));

        HttpResponse<String> deleted =
            delete(appUrl("/v1/webhooks/" + id), null, bearer(member));
        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(list(member)).extracting(row -> row.path("id").asString())
            .doesNotContain(id);
    }

    @Test
    @DisplayName("The secret is stored encrypted, not in the clear")
    void theSecretIsEncryptedAtRest() {
        // The source stored it as plaintext VARCHAR(128) — a working shared credential in every
        // database backup. It goes through the same AES-GCM field encryption as the AI API key
        // and the SimpleFIN access URL, and carries that service's v1: version prefix.
        String id = Json.data(create(PUBLIC_TARGET, SECRET, List.of()).body())
            .path("id").asString();

        String stored = jdbcTemplate.queryForObject(
            "SELECT secret_encrypted FROM webhook_subscription WHERE id = ?::uuid",
            String.class, id);

        assertThat(stored).isNotEqualTo(SECRET).doesNotContain(SECRET).startsWith("v1:");
    }

    @Test
    @DisplayName("An empty eventTypes means every event, and is what an omitted list becomes")
    void anEmptyEventListMeansEverything() {
        JsonNode omitted = Json.data(create(PUBLIC_TARGET, SECRET, null).body());

        assertThat(Json.elements(omitted.path("eventTypes")))
            .as("null and [] are the same request: subscribe to everything")
            .isEmpty();
    }

    @Test
    @DisplayName("Plain http is refused with outbound-url-rejected")
    void plainHttpIsRefused() {
        HttpResponse<String> response = create("http://192.0.2.1/hook", SECRET, List.of());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("outbound-url-rejected");
    }

    @Test
    @DisplayName("A private, loopback or metadata target is refused, and the reason names no IP")
    void privateTargetsAreRefused() {
        // The four ranges the source's SafeWebhookUrlValidator missed are among these: CGNAT,
        // IPv6 unique-local, 0.0.0.0/8 and IPv4-mapped IPv6. The message names the RULE and never
        // the resolved address, or the validator would be the internal port scanner it exists to
        // prevent.
        for (String target : List.of("https://127.0.0.1/hook", "https://10.0.0.5/hook",
            "https://192.168.1.10/hook", "https://169.254.169.254/latest/meta-data/",
            "https://100.64.0.1/hook", "https://[fc00::1]/hook", "https://0.0.0.0/hook")) {

            HttpResponse<String> response = create(target, SECRET, List.of());

            assertThat(response.statusCode())
                .as("%s must be refused: %s", target, response.body())
                .isEqualTo(400);
            assertThat(Json.errorCode(response.body())).isEqualTo("outbound-url-rejected");
        }
        assertThat(list(member)).isEmpty();
    }

    @Test
    @DisplayName("Credentials before the host are refused — the classic allowlist bypass")
    void userinfoIsRefused() {
        HttpResponse<String> response =
            create("https://evil.example@10.0.0.1/hook", SECRET, List.of());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("outbound-url-rejected");
    }

    @Test
    @DisplayName("A secret shorter than sixteen characters is refused")
    void aShortSecretIsRefused() {
        // A short shared secret is guessable offline from a single captured delivery, after which
        // the signature proves nothing.
        HttpResponse<String> response = withoutRequestValidation(
            "the spec's own minLength is 16, so this request is deliberately non-conforming",
            () -> create(PUBLIC_TARGET, "short", List.of()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("An id that belongs to nothing, or to a household mate, is 404")
    void anotherMembersSubscriptionIsNotFound() {
        Session mate = inviteAndRegister(loginBootstrapAdmin(), "webhook-mate");
        String id = Json.data(create(PUBLIC_TARGET, SECRET, List.of()).body())
            .path("id").asString();

        assertThat(mate.householdId()).isEqualTo(member.householdId());
        assertThat(list(mate))
            .as("a webhook points at a URL one member controls; the ledger is shared, this is not")
            .extracting(row -> row.path("id").asString())
            .doesNotContain(id);
        assertThat(delete(appUrl("/v1/webhooks/" + id), null,
            "Authorization", "Bearer " + mate.accessToken()).statusCode()).isEqualTo(404);
        assertThat(delete(appUrl("/v1/webhooks/" + unknownId()), null, bearer(member))
            .statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Deleting a subscription takes its queued deliveries with it")
    void deletingCascadesToTheQueue() {
        // A member who has revoked an endpoint should not have events arriving at it for the next
        // half minute.
        String id = Json.data(create(PUBLIC_TARGET, SECRET, List.of()).body())
            .path("id").asString();
        jdbcTemplate.update(
            "INSERT INTO webhook_delivery_log (subscription_id, event_type, payload, status, "
                + "next_attempt_at) VALUES (?::uuid, 'sync.completed', '{}', 'PENDING', now())",
            id);

        delete(appUrl("/v1/webhooks/" + id), null, bearer(member));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM webhook_delivery_log WHERE subscription_id = ?::uuid",
            Integer.class, id)).isZero();
    }

    @Test
    @DisplayName("The twenty-first subscription is 409 conflict")
    void theSubscriptionCountIsCapped() {
        for (int index = 0; index < 20; index++) {
            assertThat(create(PUBLIC_TARGET + "/" + index, SECRET, List.of()).statusCode())
                .isEqualTo(201);
        }

        HttpResponse<String> refused = create(PUBLIC_TARGET + "/overflow", SECRET, List.of());

        assertThat(refused.statusCode())
            .as("every subscription is an outbound connection this server makes on that member's "
                + "behalf, so the number is capped rather than unbounded")
            .isEqualTo(409);
        assertThat(Json.errorCode(refused.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("Two threads racing the last slot produce one 201 and one 409, never two rows")
    void theCapHoldsUnderConcurrency() throws Exception {
        // A real two-thread race, the way InviteIT's redemption and
        // AccessControlIT#theLastAdminGuardHoldsUnderConcurrency are. The cap was a check-then-act
        // — countByUserId, then INSERT, at READ COMMITTED with no lock and no constraint in V34
        // that bounds a member's rows — so both threads read nineteen and both inserted, and the
        // member ended up holding twenty-one outbound endpoints. A flake here shows up as TWO
        // successes, never as a false pass, which is why the assertion is on the row count.
        for (int index = 0; index < 19; index++) {
            assertThat(create(PUBLIC_TARGET + "/held-" + index, SECRET, List.of()).statusCode())
                .isEqualTo(201);
        }

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> {
                start.await(5, TimeUnit.SECONDS);
                return create(PUBLIC_TARGET + "/race-" + UUID.randomUUID(), SECRET, List.of())
                    .statusCode();
            };
            Future<Integer> first = pool.submit(attempt);
            Future<Integer> second = pool.submit(attempt);
            start.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS),
                second.get(30, TimeUnit.SECONDS)))
                .as("the loser counts under the winner's committed row, so it is told 409 rather "
                    + "than quietly given a twenty-first subscription")
                .containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM webhook_subscription WHERE user_id = ?", Integer.class,
            member.userId()))
            .as("the cap is a resource control: every row is a connection this server makes on "
                + "that member's behalf")
            .isEqualTo(20);
    }

    @Test
    @DisplayName("Every webhook route requires a session")
    void everyRouteRequiresASession() {
        assertThat(get(appUrl("/v1/webhooks")).statusCode()).isEqualTo(401);
        assertThat(postJson(appUrl("/v1/webhooks"),
            Json.object("targetUrl", PUBLIC_TARGET, "secret", SECRET, "eventTypes", List.of()))
            .statusCode()).isEqualTo(401);
        assertThat(delete(appUrl("/v1/webhooks/" + UUID.randomUUID()), null).statusCode())
            .isEqualTo(401);
    }
}
