package finance.trove.notifications.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.budgets.BudgetTestSupport;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import finance.trove.common.events.BudgetThresholdCrossedEvent;
import finance.trove.common.events.SubscriptionPriceChangedEvent;
import finance.trove.common.events.SyncCompletedEvent;
import java.net.http.HttpResponse;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wire four: a domain event becomes queued deliveries for the members who subscribed to it, and
 * for nobody else ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D3; collapse step 16, §7 row 16).
 *
 * <p>{@code WebhookDeliveryIT} drives {@code WebhookDeliveryService} directly, because at step 11
 * nothing published into its queue. This is the class that closes that: every case below starts at
 * a real published domain event and ends at a {@code webhook_delivery_log} row, so with
 * {@code WebhookEventListener} removed the queue is empty and every assertion here fails.
 *
 * <p>The target is an unroutable {@code 2d00::/8} literal, exactly as {@code WebhookIT} uses:
 * nothing connects to it. That is also the second half of what these cases assert — the rows land
 * {@code PENDING} with no attempt on them, because this step SCHEDULES and the retry job with its
 * delivery-time URL re-validation, its pinned resolver and its HMAC signature is the only thing
 * that ever dials out. A listener that delivered inline would walk straight around the SSRF fix
 * that defect D-N2 exists for.
 */
class WebhookSeamIT extends BudgetTestSupport {

    /** A public IPv6 literal that the outbound policy accepts and nothing ever reaches. */
    private static final String TARGET = "https://[2d00::1]/trove-hook";

    private static final String SECRET = "a-long-enough-webhook-secret";

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Session member;

    @BeforeEach
    void setUp() {
        member = freshHousehold("webhook-seam");
    }

    // ------------------------------------------------------------------ fixtures

    private UUID subscribe(Object eventTypes) {
        HttpResponse<String> created = postJson(appUrl("/v1/webhooks"),
            Json.object("targetUrl", TARGET, "secret", SECRET, "eventTypes", eventTypes),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(Json.data(created.body()).path("id").asString());
    }

    private void publish(Object event) {
        new TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> events.publishEvent(event));
    }

    private BudgetThresholdCrossedEvent crossing() {
        return new BudgetThresholdCrossedEvent(member.householdId(), member.userId(),
            UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 9),
            Money.of("340.00", CurrencyCode.CAD), Money.of("400.00", CurrencyCode.CAD));
    }

    private SyncCompletedEvent syncFailure() {
        return new SyncCompletedEvent(member.householdId(), member.userId(), UUID.randomUUID(),
            UUID.randomUUID(), SyncCompletedEvent.Outcome.FAILURE, 0, 0,
            "credentials rejected for account ****4417");
    }

    private List<Map<String, Object>> deliveries(UUID subscriptionId) {
        return jdbcTemplate.queryForList(
            "SELECT event_type, payload, status, attempt_count, last_status_code "
                + "FROM webhook_delivery_log WHERE subscription_id = ? ORDER BY created_at",
            subscriptionId);
    }

    // ------------------------------------------------------------------ the filter

    @Test
    @DisplayName("a subscription filtered to one event type receives that event and not the "
        + "others")
    void theEventTypeFilterIsHonoured() {
        UUID narrow = subscribe(List.of("budget.threshold_crossed"));

        publish(crossing());
        publish(syncFailure());
        publish(new SubscriptionPriceChangedEvent(member.householdId(), member.userId(),
            UUID.randomUUID(), "A STREAMING SERVICE", Money.of("16.49", CurrencyCode.CAD),
            Money.of("20.99", CurrencyCode.CAD)));

        assertThat(deliveries(narrow)).singleElement()
            .satisfies(row -> assertThat(row.get("event_type"))
                .isEqualTo("budget.threshold_crossed"));
    }

    @Test
    @DisplayName("an unfiltered subscription receives all three")
    void anEmptyFilterMeansEveryEvent() {
        UUID wide = subscribe(List.of());

        publish(crossing());
        publish(syncFailure());
        publish(new SubscriptionPriceChangedEvent(member.householdId(), member.userId(),
            UUID.randomUUID(), "A STREAMING SERVICE", Money.of("16.49", CurrencyCode.CAD),
            Money.of("20.99", CurrencyCode.CAD)));

        assertThat(deliveries(wide)).extracting(row -> row.get("event_type"))
            .containsExactlyInAnyOrder("budget.threshold_crossed", "sync.completed",
                "subscription.price_changed");
    }

    // ------------------------------------------------------------------ what it does NOT do

    @Test
    @DisplayName("the listener schedules and nothing else: no attempt, no status code, no socket")
    void nothingIsDelivered() {
        UUID subscription = subscribe(List.of("budget.threshold_crossed"));

        publish(crossing());

        // Read immediately: the fan-out is synchronous on the publishing thread and returns
        // before the retry job's next 30-second pass, so an attempt here could only have come
        // from the listener itself dialling out.
        assertThat(deliveries(subscription)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("attempt_count")).isEqualTo(0);
            assertThat(row.get("last_status_code")).isNull();
        });
    }

    @Test
    @DisplayName("the payload carries ids and amounts, and never the bank's own failure text")
    void thePayloadIsIdsAndAmounts() {
        UUID subscription = subscribe(List.of("sync.completed"));

        publish(syncFailure());

        assertThat(deliveries(subscription)).singleElement().satisfies(row -> {
            String payload = (String) row.get("payload");
            assertThat(payload).contains("\"event\":\"sync.completed\"")
                .contains("\"outcome\":\"FAILURE\"")
                .contains("\"householdId\":\"" + member.householdId() + "\"");
            assertThat(payload)
                .as("a delivery is an outbound request to an address a member typed in; the "
                    + "receiver reads the detail from the member's own API")
                .doesNotContain("4417").doesNotContain("credentials");
        });
    }

    @Test
    @DisplayName("a disabled subscription is queued nothing")
    void aDisabledSubscriptionIsSkipped() {
        UUID subscription = subscribe(List.of());
        jdbcTemplate.update("UPDATE webhook_subscription SET enabled = false WHERE id = ?",
            subscription);

        publish(crossing());

        assertThat(deliveries(subscription)).isEmpty();
    }

    // ------------------------------------------------------------------ who is addressed

    @Test
    @DisplayName("a household-scoped crossing reaches a household mate's endpoint too")
    void aCrossingReachesEveryMembersEndpoint() {
        UUID mine = subscribe(List.of("budget.threshold_crossed"));
        Session mate = inviteAndRegister(member, "webhook-seam-mate");
        HttpResponse<String> created = postJson(appUrl("/v1/webhooks"),
            Json.object("targetUrl", TARGET, "secret", SECRET, "eventTypes",
                List.of("budget.threshold_crossed")),
            "Authorization", "Bearer " + mate.accessToken());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID theirs = UUID.fromString(Json.data(created.body()).path("id").asString());

        publish(crossing());

        assertThat(deliveries(mine))
            .as("a budget is the household's shared plan and any member's spending can cross it")
            .hasSize(1);
        assertThat(deliveries(theirs)).hasSize(1);
    }

    @Test
    @DisplayName("a member-scoped event never reaches a household mate's endpoint")
    void aMemberScopedEventIsNotFannedOut() {
        UUID mine = subscribe(List.of());
        Session mate = inviteAndRegister(member, "webhook-seam-mate2");
        HttpResponse<String> created = postJson(appUrl("/v1/webhooks"),
            Json.object("targetUrl", TARGET, "secret", SECRET, "eventTypes", List.of()),
            "Authorization", "Bearer " + mate.accessToken());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID theirs = UUID.fromString(Json.data(created.body()).path("id").asString());

        publish(syncFailure());

        assertThat(deliveries(mine))
            .as("the connection belongs to the member the event names")
            .hasSize(1);
        assertThat(deliveries(theirs))
            .as("one member's bank connection must never appear in another member's integration")
            .isEmpty();
    }
}
