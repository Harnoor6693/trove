package finance.trove.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The shared setup for collapse step 10's integration tests: a merchant nobody else in the suite
 * uses, a charge history behind it, and the six subscription routes.
 *
 * <p><strong>Every merchant name is unique per test.</strong> V29 puts
 * {@code UNIQUE (household_id, merchant_name)} on {@code detected_subscription}, the whole suite
 * shares one household and one database, and no test truncates anything — so two tests that both
 * seeded "SPOTIFY" would be writing to one another's row and each would pass or fail on execution
 * order. {@link #uniqueMerchant} is what keeps them independent; it also has to survive
 * {@code MerchantNormaliser}, which upper-cases, strips a trailing digit-bearing reference and
 * collapses punctuation, so the generated name is already in that canonical form.
 *
 * <p>Abstract, and without the {@code IT} suffix, so Failsafe does not try to run it.
 */
public abstract class SubscriptionSupport extends LedgerSupport {

    /** A merchant name no other test uses, already in {@code MerchantNormaliser}'s canonical form. */
    protected static String uniqueMerchant(String prefix) {
        // The trailing " CO" is load-bearing. MerchantNormaliser strips a trailing run of four or
        // more alphanumerics that contains a digit — exactly what a random hex token looks like —
        // so a name ending in one would normalise away its own uniqueness and every test would
        // collide on the same row. Two trailing letters are too short for that pass to match.
        String name = (prefix + " " + UUID.randomUUID().toString().substring(0, 8) + " CO")
            .toUpperCase(java.util.Locale.ROOT);
        assertThat(finance.trove.categorisation.MerchantNormaliser.normalise(name))
            .as("the fixture must already be canonical, or the row it seeds and the row detection "
                + "writes are two different merchants")
            .isEqualTo(name);
        return name;
    }

    /**
     * Records {@code count} DEBITs of one amount, one calendar month apart, the most recent
     * {@code endingDaysAgo} days before the caller's own today.
     *
     * <p>Thirty-day steps rather than {@code minusMonths}, so the gaps are exactly the 30 days
     * {@code BillingCycle.MONTHLY} is centred on whatever month the suite happens to run in.
     *
     * @return the charge dates, oldest first
     */
    protected List<LocalDate> seedMonthlyCharges(Session session, String accountId,
                                                 String merchant, String amount, int count,
                                                 int endingDaysAgo) {
        LocalDate lastSeen = LocalDate.parse(todayForCaller(session)).minusDays(endingDaysAgo);
        java.util.List<LocalDate> dates = new java.util.ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            dates.add(lastSeen.minusDays(30L * i));
        }
        for (LocalDate date : dates) {
            createManualTransaction(session, accountId, amount, date.toString(), "DEBIT", merchant);
        }
        return List.copyOf(dates);
    }

    /** {@code POST /v1/subscriptions/scan} — the manual trigger; returns the active list. */
    protected List<JsonNode> scan(Session session) {
        // No body at all: the operation declares no requestBody, so a JSON content type with a
        // zero-length payload would be a request no client makes and the contract does not
        // describe.
        HttpResponse<String> response = exchange("POST", appUrl("/v1/subscriptions/scan"), null,
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("the manual scan failed: %s", response.body())
            .isEqualTo(200);
        return Json.elements(Json.data(response.body()));
    }

    /** {@code GET /v1/subscriptions} — the household's active rows. */
    protected List<JsonNode> listSubscriptions(Session session) {
        HttpResponse<String> response = get(appUrl("/v1/subscriptions"), session.accessToken());
        assertThat(response.statusCode())
            .as("listing subscriptions failed: %s", response.body())
            .isEqualTo(200);
        return Json.elements(Json.data(response.body()));
    }

    /** The one row for {@code merchant}, or a failed assertion naming what was there instead. */
    protected JsonNode subscriptionFor(List<JsonNode> rows, String merchant) {
        List<JsonNode> matches = rows.stream()
            .filter(row -> merchant.equals(row.path("merchantName").asString()))
            .toList();
        assertThat(matches)
            .as("expected exactly one detection for %s, saw %s", merchant,
                rows.stream().map(row -> row.path("merchantName").asString()).toList())
            .hasSize(1);
        return matches.getFirst();
    }

    /** {@code GET /v1/subscriptions/summary}. */
    protected JsonNode summary(Session session) {
        HttpResponse<String> response =
            get(appUrl("/v1/subscriptions/summary"), session.accessToken());
        assertThat(response.statusCode())
            .as("reading the subscription summary failed: %s", response.body())
            .isEqualTo(200);
        return Json.data(response.body());
    }

    /** {@code GET /v1/subscriptions/alerts} — the unread feed. */
    protected List<JsonNode> listAlerts(Session session) {
        HttpResponse<String> response =
            get(appUrl("/v1/subscriptions/alerts"), session.accessToken());
        assertThat(response.statusCode())
            .as("listing subscription alerts failed: %s", response.body())
            .isEqualTo(200);
        return Json.elements(Json.data(response.body()));
    }

    /** How many rows {@code detected_subscription} holds for a household, cancelled included. */
    protected int storedSubscriptionCount(UUID householdId, String merchant) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM detected_subscription WHERE household_id = ? "
                + "AND merchant_name = ?", Integer.class, householdId, merchant);
        return count == null ? 0 : count;
    }
}
