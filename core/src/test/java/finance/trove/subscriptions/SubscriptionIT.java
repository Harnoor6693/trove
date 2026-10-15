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
 * The six subscription routes of plan §4.8, end to end, including the one that surprises clients:
 * {@code DELETE /v1/subscriptions/{id}} is a SOFT delete and the row is still readable afterwards.
 */
class SubscriptionIT extends SubscriptionSupport {

    private Session admin;
    private String accountId;

    @BeforeEach
    void signIn() {
        admin = loginBootstrapAdmin();
        accountId = createManualAccount(admin, "Subscriptions " + UUID.randomUUID());
    }

    @Test
    @DisplayName("A scan detects a monthly charge, and every field of the row is what was charged")
    void aScanDetectsAMonthlyCharge() {
        String merchant = uniqueMerchant("STREAMER");
        List<LocalDate> dates = seedMonthlyCharges(admin, accountId, merchant, "9.99", 3, 1);

        JsonNode row = subscriptionFor(scan(admin), merchant);

        assertThat(row.path("merchantName").asString()).isEqualTo(merchant);
        assertThat(row.path("billingCycle").asString()).isEqualTo("MONTHLY");
        assertThat(row.path("currency").asString()).isEqualTo("CAD");
        assertThat(row.path("normalizedAmount").asString())
            .as("a decimal STRING at the column's NUMERIC(19,4) scale, never a JSON number")
            .isEqualTo("9.9900");
        assertThat(row.path("previousAmount").isNull())
            .as("nothing has changed price yet")
            .isTrue();
        assertThat(row.path("priceChanged").asBoolean()).isFalse();
        assertThat(row.path("signal").asString()).isEqualTo("GREEN");
        assertThat(row.path("cancelled").asBoolean()).isFalse();
        assertThat(row.path("firstSeenDate").asString()).isEqualTo(dates.getFirst().toString());
        assertThat(row.path("lastSeenDate").asString()).isEqualTo(dates.getLast().toString());
        assertThat(row.path("nextChargeDate").asString())
            .as("last seen advanced by exactly one calendar month")
            .isEqualTo(dates.getLast().plusMonths(1).toString());
        assertThat(row.path("householdId").asString())
            .isEqualTo(admin.householdId().toString());
        assertThat(row.path("createdBy").asString())
            .as("the member the run was attributed to; audit only, never the scope")
            .isEqualTo(admin.userId().toString());
    }

    @Test
    @DisplayName("A detected subscription is on the list and readable by its own id")
    void aDetectedSubscriptionIsListedAndReadableById() {
        String merchant = uniqueMerchant("READABLE");
        seedMonthlyCharges(admin, accountId, merchant, "14.50", 3, 1);
        String id = subscriptionFor(scan(admin), merchant).path("id").asString();

        assertThat(listSubscriptions(admin).stream()
            .map(row -> row.path("id").asString()).toList()).contains(id);

        HttpResponse<String> response =
            get(appUrl("/v1/subscriptions/" + id), admin.accessToken());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.data(response.body()).path("merchantName").asString()).isEqualTo(merchant);
    }

    @Test
    @DisplayName("DELETE is a SOFT delete: the row leaves the list and stays readable by id")
    void deleteIsASoftDelete() {
        String merchant = uniqueMerchant("DISMISSED");
        seedMonthlyCharges(admin, accountId, merchant, "5.00", 3, 1);
        String id = subscriptionFor(scan(admin), merchant).path("id").asString();

        HttpResponse<String> deleted = delete(appUrl("/v1/subscriptions/" + id), null,
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(deleted.body())
            .as("the closed EmptySuccess body: data is omitted, not null")
            .isEqualTo("{\"success\":true}");

        assertThat(listSubscriptions(admin).stream()
            .map(row -> row.path("id").asString()).toList())
            .as("it leaves the active list")
            .doesNotContain(id);

        HttpResponse<String> stillThere =
            get(appUrl("/v1/subscriptions/" + id), admin.accessToken());
        assertThat(stillThere.statusCode())
            .as("and is still readable by id — that is what makes this DELETE worth documenting")
            .isEqualTo(200);
        assertThat(Json.data(stillThere.body()).path("cancelled").asBoolean()).isTrue();
        assertThat(storedSubscriptionCount(admin.householdId(), merchant))
            .as("the row survives in the database")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("Dismissing twice succeeds: a client that retries does not get a 404")
    void theSoftDeleteIsIdempotent() {
        String merchant = uniqueMerchant("TWICE");
        seedMonthlyCharges(admin, accountId, merchant, "5.00", 3, 1);
        String id = subscriptionFor(scan(admin), merchant).path("id").asString();

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThat(delete(appUrl("/v1/subscriptions/" + id), null,
                "Authorization", "Bearer " + admin.accessToken()).statusCode())
                .as("attempt %d", attempt + 1)
                .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("A dismissed subscription is kept current by the scan but never un-cancelled")
    void aScanNeverResurrectsADismissedSubscription() {
        String merchant = uniqueMerchant("STAYS DISMISSED");
        seedMonthlyCharges(admin, accountId, merchant, "5.00", 3, 1);
        String id = subscriptionFor(scan(admin), merchant).path("id").asString();
        delete(appUrl("/v1/subscriptions/" + id), null,
            "Authorization", "Bearer " + admin.accessToken());

        scan(admin);

        assertThat(Json.data(get(appUrl("/v1/subscriptions/" + id), admin.accessToken()).body())
            .path("cancelled").asBoolean())
            .as("otherwise a member would dismiss the same merchant every night")
            .isTrue();
    }

    @Test
    @DisplayName("The summary reports the caller's own currency and never blends two")
    void theSummaryReportsPerCurrency() {
        String merchant = uniqueMerchant("SUMMARISED");
        seedMonthlyCharges(admin, accountId, merchant, "11.00", 3, 1);
        scan(admin);

        JsonNode summary = summary(admin);

        assertThat(summary.path("currency").asString())
            .as("the CALLING member's users.currency, never a literal (finding F1 / SD-3)")
            .isEqualTo("CAD");
        assertThat(summary.path("activeCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(summary.has("monthlyRunRate"))
            .as("there is deliberately no top-level money figure: a household with two "
                + "currencies has no single monthly total")
            .isFalse();

        List<JsonNode> byCurrency = Json.elements(summary.path("byCurrency"));
        assertThat(byCurrency).isNotEmpty();
        JsonNode cad = byCurrency.stream()
            .filter(entry -> "CAD".equals(entry.path("currency").asString()))
            .findFirst().orElseThrow();
        assertThat(cad.path("activeCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(new java.math.BigDecimal(cad.path("monthlyRunRate").asString()))
            .as("a MONTHLY subscription contributes its own amount to the run-rate")
            .isGreaterThanOrEqualTo(new java.math.BigDecimal("11.00"));
    }

    @Test
    @DisplayName("The next-fourteen-days list agrees with the rows' own projected dates")
    void theUpcomingListAgreesWithTheRows() {
        String merchant = uniqueMerchant("UPCOMING");
        // Last seen 23 days ago, so the projection lands about a week from today — inside the
        // fourteen-day window whatever month the suite runs in.
        seedMonthlyCharges(admin, accountId, merchant, "7.25", 3, 23);
        JsonNode row = subscriptionFor(scan(admin), merchant);
        LocalDate due = LocalDate.parse(row.path("nextChargeDate").asString());
        LocalDate today = LocalDate.parse(todayForCaller(admin));

        List<JsonNode> upcoming = Json.elements(summary(admin).path("next14Days"));
        List<String> merchantsDue = upcoming.stream()
            .flatMap(day -> Json.elements(day.path("subscriptions")).stream())
            .map(sub -> sub.path("merchantName").asString())
            .toList();

        if (!due.isBefore(today) && !due.isAfter(today.plusDays(14))) {
            assertThat(merchantsDue).contains(merchant);
            JsonNode day = upcoming.stream()
                .filter(entry -> due.toString().equals(entry.path("date").asString()))
                .findFirst().orElseThrow();
            assertThat(Json.elements(day.path("totals")))
                .as("a day's totals are one entry per currency present, never one blended figure")
                .isNotEmpty()
                .allSatisfy(total -> assertThat(total.path("currency").asString()).isNotBlank());
        } else {
            assertThat(merchantsDue)
                .as("a projection outside the window is not in the list")
                .doesNotContain(merchant);
        }
    }

    @Test
    @DisplayName("A charge projected into the past is RED and is left out of the upcoming list")
    void anOverdueChargeIsRedAndNotUpcoming() {
        String merchant = uniqueMerchant("OVERDUE");
        // Last seen 90 days ago: the projection was due about 60 days back, far outside MONTHLY's
        // three-day tolerance, so the charge is missing rather than merely late.
        seedMonthlyCharges(admin, accountId, merchant, "3.50", 3, 90);
        JsonNode row = subscriptionFor(scan(admin), merchant);

        assertThat(row.path("signal").asString()).isEqualTo("RED");
        assertThat(LocalDate.parse(row.path("nextChargeDate").asString()))
            .as("a stale projection is left stale rather than rolled forward")
            .isBefore(LocalDate.parse(todayForCaller(admin)));
        assertThat(Json.elements(summary(admin).path("next14Days")).stream()
            .flatMap(day -> Json.elements(day.path("subscriptions")).stream())
            .map(sub -> sub.path("merchantName").asString()).toList())
            .as("telling a member money is about to leave their account, when what happened is "
                + "that it did not, would be worse than saying nothing")
            .doesNotContain(merchant);
    }

    @Test
    @DisplayName("B2-09: a subscription that stopped charging leaves the list and the run rate")
    void aStoppedSubscriptionLeavesTheListAndTheTotals() {
        String merchant = uniqueMerchant("STOPPED");
        int activeBefore = summary(admin).path("activeCount").asInt();
        // Last charged 200 days ago: the next one was due about 170 days back, six cycles missed.
        seedMonthlyCharges(admin, accountId, merchant, "3.50", 3, 200);

        assertThat(scan(admin).stream().map(row -> row.path("merchantName").asString()).toList())
            .doesNotContain(merchant);
        assertThat(listSubscriptions(admin).stream()
            .map(row -> row.path("merchantName").asString()).toList())
            .doesNotContain(merchant);
        assertThat(summary(admin).path("activeCount").asInt())
            .as("the run rate counts only subscriptions that are still charging")
            .isEqualTo(activeBefore);
    }

    @Test
    @DisplayName("Occasional spending at one merchant is not a subscription")
    void variableSpendingIsNotDetected() {
        String merchant = uniqueMerchant("CORNER SHOP");
        LocalDate today = LocalDate.parse(todayForCaller(admin));
        createManualTransaction(admin, accountId, "4.00", today.minusDays(61).toString(),
            "DEBIT", merchant);
        createManualTransaction(admin, accountId, "19.00", today.minusDays(30).toString(),
            "DEBIT", merchant);
        createManualTransaction(admin, accountId, "42.00", today.minusDays(1).toString(),
            "DEBIT", merchant);

        assertThat(scan(admin).stream().map(row -> row.path("merchantName").asString()).toList())
            .as("three different prices is a shop, not a subscription")
            .doesNotContain(merchant);
    }

    @Test
    @DisplayName("The alerts feed answers, and is empty of anything this test raised")
    void theAlertsFeedAnswers() {
        assertThat(listAlerts(admin)).allSatisfy(alert ->
            assertThat(alert.path("isRead").asBoolean())
                .as("the feed is the UNREAD alerts")
                .isFalse());
    }

    @Test
    @DisplayName("An id that belongs to nothing is 404 on every by-id route")
    void anUnknownIdIsNotFound() {
        UUID unknown = unknownId();

        assertThat(get(appUrl("/v1/subscriptions/" + unknown), admin.accessToken()).statusCode())
            .isEqualTo(404);
        assertThat(delete(appUrl("/v1/subscriptions/" + unknown), null,
            "Authorization", "Bearer " + admin.accessToken()).statusCode()).isEqualTo(404);
        assertThat(exchange("PATCH", appUrl("/v1/subscriptions/alerts/" + unknown + "/read"), null,
            "Authorization", "Bearer " + admin.accessToken()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Every subscription route requires a session")
    void everyRouteRequiresASession() {
        UUID unknown = unknownId();

        assertThat(get(appUrl("/v1/subscriptions")).statusCode()).isEqualTo(401);
        assertThat(get(appUrl("/v1/subscriptions/summary")).statusCode()).isEqualTo(401);
        assertThat(get(appUrl("/v1/subscriptions/alerts")).statusCode()).isEqualTo(401);
        assertThat(get(appUrl("/v1/subscriptions/" + unknown)).statusCode()).isEqualTo(401);
        assertThat(exchange("POST", appUrl("/v1/subscriptions/scan"), null).statusCode())
            .isEqualTo(401);
        assertThat(delete(appUrl("/v1/subscriptions/" + unknown), null).statusCode())
            .isEqualTo(401);
        assertThat(exchange("PATCH", appUrl("/v1/subscriptions/alerts/" + unknown + "/read"), null)
            .statusCode()).isEqualTo(401);
    }
}
