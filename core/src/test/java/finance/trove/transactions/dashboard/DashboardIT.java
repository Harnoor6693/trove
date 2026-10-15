package finance.trove.transactions.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /v1/dashboard/today} ({@code docs/recovery/07-READINESS-GAPS.md} P2-15).
 *
 * <p>Two things are proved here that the pre-collapse controller got wrong: the boundaries are the
 * caller's own, read from {@code users.timezone} rather than from the container's UTC clock; and
 * currencies are reported separately rather than added together. The third — that an anonymous
 * caller is refused rather than answered with a zero-valued body — is asserted in
 * {@code AccessControlIT} alongside every other route of this step.
 *
 * <p>Assertions are relative: the suite shares one household, so the figures are read before and
 * after a known write and the difference is what is asserted. An absolute total would be a promise
 * about every other test in the run.
 */
class DashboardIT extends LedgerSupport {

    @Test
    @DisplayName("The boundaries are the caller's own timezone, not the container's")
    void theBoundariesAreTheCallersOwn() {
        Session admin = loginBootstrapAdmin();
        String email = uniqueEmail("dashboard-vancouver");
        assertThat(postJson(appUrl("/v1/auth/register"),
            Json.object("inviteToken", createInvite(admin, null), "email", email,
                "password", MEMBER_PASSWORD, "displayName", "Pacific Member", "currency", "CAD",
                "country", "CA", "timezone", "America/Vancouver"),
            "X-Trove-Auth-Mode", "bearer").statusCode()).isEqualTo(201);
        Session pacific = loginBearer(email, MEMBER_PASSWORD);

        JsonNode response = Json.data(
            get(appUrl("/v1/dashboard/today"), pacific.accessToken()).body());

        assertThat(response.path("timezone").asString())
            .as("§9 risk 21: without the table-backed @Primary UserTimeZoneProvider this would be "
                + "the install-wide default and users.timezone would be decorative")
            .isEqualTo("America/Vancouver");
        assertThat(LocalDate.parse(response.path("date").asString()))
            .as("a Vancouver member's evening purchase must land on their today, not on "
                + "tomorrow's dashboard")
            .isEqualTo(LocalDate.now(ZoneId.of("America/Vancouver")));
    }

    @Test
    @DisplayName("A DEBIT raises spending, a CREDIT raises income, and a REFUND nets off spending")
    void theAggregateFollowsTheSameRuleAsABudgetEnvelope() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Dashboard " + UUID.randomUUID());
        String today = todayForCaller(admin);
        JsonNode before = dashboard(admin);

        createManualTransaction(admin, accountId, "40.00", today, "DEBIT", "A purchase");
        createManualTransaction(admin, accountId, "15.00", today, "REFUND", "A return");
        createManualTransaction(admin, accountId, "100.00", today, "CREDIT", "Payday");
        JsonNode after = dashboard(admin);

        assertThat(money(after, "spentToday").subtract(money(before, "spentToday")))
            .as("§6 D1 nets a REFUND against DEBIT spend, and the dashboard must agree with the "
                + "budget or two screens disagree about the same month")
            .isEqualByComparingTo("25.00");
        assertThat(money(after, "incomeThisMonth").subtract(money(before, "incomeThisMonth")))
            .isEqualByComparingTo("100.00");
        assertThat(after.path("transactionsToday").asInt() - before.path("transactionsToday")
            .asInt()).isEqualTo(3);
        assertThat(money(after, "netThisMonth"))
            .isEqualByComparingTo(
                money(after, "incomeThisMonth").subtract(money(after, "spentThisMonth")));
    }

    @Test
    @DisplayName("A FEE is a real movement but is not discretionary spending")
    void onlyDebitsAndRefundsMoveSpending() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Dashboard " + UUID.randomUUID());
        String today = todayForCaller(admin);
        JsonNode before = dashboard(admin);

        createManualTransaction(admin, accountId, "3.50", today, "FEE", "Monthly account fee");
        JsonNode after = dashboard(admin);

        assertThat(money(after, "spentToday"))
            .as("folding a bank charge into spending makes a budget the member cannot reconcile "
                + "against their own statement")
            .isEqualByComparingTo(money(before, "spentToday"));
        assertThat(after.path("transactionsToday").asInt())
            .as("it is still a transaction that happened today")
            .isEqualTo(before.path("transactionsToday").asInt() + 1);
    }

    @Test
    @DisplayName("Currencies are subtotalled separately and never added together")
    void currenciesAreNeverConverted() {
        Session admin = loginBootstrapAdmin();
        String canadian = createManualAccount(admin, "CAD " + UUID.randomUUID());
        String american = createAccount(admin, MANUAL, "USD " + UUID.randomUUID(), "OTHER", "USD");
        String today = todayForCaller(admin);
        JsonNode before = dashboard(admin);

        createManualTransaction(admin, canadian, "10.00", today, "DEBIT", "A CAD purchase");
        createManualTransaction(admin, american, "7.00", today, "DEBIT", "A USD purchase");
        JsonNode after = dashboard(admin);

        assertThat(after.path("currency").asString())
            .as("the headline is the caller's own users.currency")
            .isEqualTo("CAD");
        assertThat(money(after, "spentToday").subtract(money(before, "spentToday")))
            .as("the headline carries the CAD subtotal alone; there is no exchange rate anywhere "
                + "in this product, so adding USD to it would be inventing a number")
            .isEqualByComparingTo("10.00");

        List<JsonNode> byCurrency = Json.elements(after.path("byCurrency"));
        assertThat(byCurrency.stream().map(entry -> entry.path("currency").asString()).toList())
            .as("ordered by code, one entry per currency actually present")
            .contains("CAD", "USD").isSorted();
        JsonNode usd = byCurrency.stream()
            .filter(entry -> "USD".equals(entry.path("currency").asString()))
            .findFirst()
            .orElseThrow();
        assertThat(new BigDecimal(usd.path("spentToday").asString()))
            .isGreaterThanOrEqualTo(new BigDecimal("7.00"));
    }

    @Test
    @DisplayName("Every money field is a decimal string, never a JSON number")
    void moneyIsAStringOnTheWire() {
        Session admin = loginBootstrapAdmin();

        JsonNode response = dashboard(admin);

        for (String field : List.of("spentToday", "spentThisMonth", "incomeThisMonth",
            "netThisMonth")) {
            assertThat(response.path(field).isTextual())
                .as("§5.4: a ledger figure parsed into an IEEE-754 double cannot round-trip "
                    + "0.1 + 0.2, so every BigDecimal on this API is a string")
                .isTrue();
            assertThat(response.path(field).asString())
                .as("plain notation, never 1E+2, which no client's decimal parser accepts")
                .doesNotContain("E");
        }
    }

    @Test
    @DisplayName("A household mate sees the household's figures, not their own")
    void theDashboardIsHouseholdScoped() {
        Session admin = loginBootstrapAdmin();
        Session mate = inviteAndRegister(admin, "dashboard-mate");
        String accountId = createManualAccount(admin, "Dashboard " + UUID.randomUUID());
        // The MATE's today, not the admin's. The two members are in one household but not
        // necessarily in one timezone — the bootstrap admin is on the install default and a
        // registered member chooses their own — so for several hours a day their "today" is a
        // different calendar date. Dating the row by the admin's day and then asserting on the
        // mate's dashboard would be testing the timezone feature by accident and failing at it.
        String today = todayForCaller(mate);
        JsonNode before = dashboard(mate);

        createManualTransaction(admin, accountId, "60.00", today, "DEBIT", "The admin's purchase");

        assertThat(money(dashboard(mate), "spentToday").subtract(money(before, "spentToday")))
            .as("one shared ledger per household — a per-user aggregate would show a member "
                + "nothing their partner spent")
            .isEqualByComparingTo("60.00");
    }

    private JsonNode dashboard(Session session) {
        return Json.data(get(appUrl("/v1/dashboard/today"), session.accessToken()).body());
    }

    private static BigDecimal money(JsonNode response, String field) {
        return new BigDecimal(response.path(field).asString());
    }
}
