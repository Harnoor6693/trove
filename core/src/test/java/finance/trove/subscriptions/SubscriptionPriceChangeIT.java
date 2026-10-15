package finance.trove.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A raised price: the row that records it, the single alert it writes, and the acknowledgement
 * that takes that alert off the feed.
 *
 * <p>The event {@code SubscriptionPriceChangedEvent} has no consumer yet — notifications are
 * step 11 and webhooks step 16 — so "published exactly once" is asserted in
 * {@code SubscriptionDetectionServiceTest}, where it is observable. What IS observable from
 * outside is the alert row, which is written in the same branch.
 */
class SubscriptionPriceChangeIT extends SubscriptionSupport {

    private Session admin;
    private String accountId;

    @BeforeEach
    void signIn() {
        admin = loginBootstrapAdmin();
        accountId = createManualAccount(admin, "Price change " + UUID.randomUUID());
    }

    @Test
    @DisplayName("A raised amount writes exactly one alert, and a repeat scan writes no more")
    void aRaisedAmountWritesOneAlert() {
        String merchant = uniqueMerchant("RISER");
        LocalDate today = LocalDate.parse(todayForCaller(admin));
        // Three charges at the old price, ending 31 days ago, so the fourth is a month later.
        seedMonthlyCharges(admin, accountId, merchant, "9.99", 3, 31);
        JsonNode before = subscriptionFor(scan(admin), merchant);

        assertThat(before.path("normalizedAmount").asString()).isEqualTo("9.9900");
        assertThat(before.path("priceChanged").asBoolean()).isFalse();
        assertThat(before.path("previousAmount").isNull()).isTrue();
        assertThat(before.path("signal").asString()).isEqualTo("GREEN");
        assertThat(alertsFor(before.path("id").asString())).isEmpty();

        createManualTransaction(admin, accountId, "12.99", today.minusDays(1).toString(),
            "DEBIT", merchant);
        JsonNode after = subscriptionFor(scan(admin), merchant);

        assertThat(after.path("id").asString())
            .as("the same row, updated — not a second one")
            .isEqualTo(before.path("id").asString());
        assertThat(after.path("normalizedAmount").asString()).isEqualTo("12.9900");
        assertThat(after.path("previousAmount").asString())
            .as("what it cost before the change, in the same currency")
            .isEqualTo("9.9900");
        assertThat(after.path("priceChanged").asBoolean()).isTrue();
        assertThat(after.path("signal").asString())
            .as("AMBER: the price moved and the member has not been told about this row yet")
            .isEqualTo("AMBER");

        List<JsonNode> raised = alertsFor(after.path("id").asString());
        assertThat(raised).hasSize(1);
        JsonNode alert = raised.getFirst();
        assertThat(alert.path("alertType").asString()).isEqualTo("PRICE_INCREASE");
        assertThat(alert.path("isRead").asBoolean()).isFalse();
        assertThat(alert.path("householdId").asString())
            .isEqualTo(admin.householdId().toString());
        assertThat(alert.path("alertMessage").asString())
            .as("both amounts with their currency, and no CAD literal in the code that wrote it")
            .contains(merchant, "9.99 CAD", "12.99 CAD");

        scan(admin);
        scan(admin);
        assertThat(alertsFor(after.path("id").asString()))
            .as("published on the CHANGE, not on every scan: price_changed and previous_amount "
                + "are sticky, so a later scan finds nothing to compare")
            .hasSize(1);
    }

    @Test
    @DisplayName("Acknowledging an alert takes it off the feed and is idempotent")
    void acknowledgingAnAlertTakesItOffTheFeed() {
        String merchant = uniqueMerchant("ACKED");
        LocalDate today = LocalDate.parse(todayForCaller(admin));
        seedMonthlyCharges(admin, accountId, merchant, "5.00", 3, 31);
        String subscriptionId = subscriptionFor(scan(admin), merchant).path("id").asString();
        createManualTransaction(admin, accountId, "7.50", today.minusDays(1).toString(),
            "DEBIT", merchant);
        scan(admin);

        String alertId = alertsFor(subscriptionId).getFirst().path("id").asString();

        HttpResponse<String> acknowledged = markRead(alertId);
        assertThat(acknowledged.statusCode()).isEqualTo(200);
        JsonNode body = Json.data(acknowledged.body());
        assertThat(body.path("id").asString()).isEqualTo(alertId);
        assertThat(body.path("isRead").asBoolean()).isTrue();

        assertThat(alertsFor(subscriptionId))
            .as("the unread feed is what GET /v1/subscriptions/alerts returns")
            .isEmpty();

        assertThat(markRead(alertId).statusCode())
            .as("acknowledging an already-read alert succeeds rather than conflicting")
            .isEqualTo(200);

        Integer stillStored = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM subscription_alert WHERE id = ?", Integer.class,
            UUID.fromString(alertId));
        assertThat(stillStored)
            .as("alerts are an audit trail and are never deleted")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("A price DROP moves the row but raises no PRICE_INCREASE alert")
    void aPriceDropRaisesNoAlert() {
        String merchant = uniqueMerchant("FALLER");
        LocalDate today = LocalDate.parse(todayForCaller(admin));
        seedMonthlyCharges(admin, accountId, merchant, "20.00", 3, 31);
        String subscriptionId = subscriptionFor(scan(admin), merchant).path("id").asString();

        createManualTransaction(admin, accountId, "12.00", today.minusDays(1).toString(),
            "DEBIT", merchant);
        JsonNode after = subscriptionFor(scan(admin), merchant);

        assertThat(after.path("normalizedAmount").asString()).isEqualTo("12.0000");
        assertThat(after.path("previousAmount").asString()).isEqualTo("20.0000");
        assertThat(after.path("priceChanged").asBoolean()).isTrue();
        assertThat(alertsFor(subscriptionId))
            .as("AlertType has no PRICE_DECREASE, and a row typed \"increase\" describing a fall "
                + "would be a lie")
            .isEmpty();
    }

    @Test
    @DisplayName("A dismissed subscription whose price then moves raises no alert")
    void aDismissedSubscriptionRaisesNoAlert() {
        String merchant = uniqueMerchant("DISMISSED RISER");
        LocalDate today = LocalDate.parse(todayForCaller(admin));
        seedMonthlyCharges(admin, accountId, merchant, "3.00", 3, 31);
        String subscriptionId = subscriptionFor(scan(admin), merchant).path("id").asString();
        delete(appUrl("/v1/subscriptions/" + subscriptionId), null,
            "Authorization", "Bearer " + admin.accessToken());

        createManualTransaction(admin, accountId, "9.00", today.minusDays(1).toString(),
            "DEBIT", merchant);
        scan(admin);

        assertThat(alertsFor(subscriptionId))
            .as("the member has asked not to hear about this one")
            .isEmpty();
        assertThat(Json.data(get(appUrl("/v1/subscriptions/" + subscriptionId),
            admin.accessToken()).body()).path("cancelled").asBoolean())
            .as("and the scan keeps its figures current without ever un-cancelling it")
            .isTrue();
    }

    /** The unread alerts raised against one subscription. */
    private List<JsonNode> alertsFor(String subscriptionId) {
        return listAlerts(admin).stream()
            .filter(alert -> subscriptionId.equals(alert.path("subscriptionId").asString()))
            .toList();
    }

    private HttpResponse<String> markRead(String alertId) {
        return exchange("PATCH", appUrl("/v1/subscriptions/alerts/" + alertId + "/read"), null,
            "Authorization", "Bearer " + admin.accessToken());
    }
}
