package finance.trove.budgets;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code /v1/budgets} end to end ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6).
 *
 * <p>Every request and response here is validated against {@code openapi/trove-core.yaml} by
 * {@code OpenApiConformance}, wired into {@code BaseIT.send} — so these also assert that the
 * contract this step wrote and the server it wrote match.
 */
class BudgetIT extends BudgetTestSupport {

    @Test
    @DisplayName("a period is created with the month normalised to the first, and read back")
    void aPeriodIsCreatedAndReadBack() {
        Session member = freshHousehold("budget-create");

        JsonNode created = createPeriod(member, "2026-09-23", "5000.00", "CAD", "ROLLOVER");

        assertThat(created.path("month").asString())
            .as("any day of the month is accepted and stored as the first of it")
            .isEqualTo("2026-09-01");
        assertThat(new BigDecimal(created.path("totalIncome").asString()))
            .as("money is a decimal STRING on the wire, never a JSON number a client could parse "
                + "into an IEEE-754 double")
            .isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(created.path("budgetType").asString()).isEqualTo("ROLLOVER");
        assertThat(created.path("householdId").asString())
            .isEqualTo(member.householdId().toString());
        assertThat(created.path("createdBy").asString())
            .as("created-by is audit, and it is the member who opened it")
            .isEqualTo(member.userId().toString());
        assertThat(created.path("rolledOverAt").isNull())
            .as("nothing has carried this period forward yet")
            .isTrue();

        JsonNode read = Json.data(get(appUrl("/v1/budgets/" + created.path("id").asString()),
            member.accessToken()).body());
        assertThat(read.path("id").asString()).isEqualTo(created.path("id").asString());
    }

    @Test
    @DisplayName("an omitted currency comes from the creating member, never from a CAD literal")
    void currencyDefaultsToTheMember() {
        Session member = freshHousehold("budget-currency");

        HttpResponse<String> response = postJson(appUrl("/v1/budgets"),
            Json.object("month", "2026-09-01", "totalIncome", "4000.00", "currency", null,
                "budgetType", null),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(Json.data(response.body()).path("currency").asString())
            .as("finding F1: the source shipped twelve executable CAD literals; the value now "
                + "comes from users.currency, which registration set from the member's country")
            .isEqualTo("CAD");
        assertThat(Json.data(response.body()).path("budgetType").asString())
            .as("an omitted type is FLEXIBLE")
            .isEqualTo("FLEXIBLE");
    }

    @Test
    @DisplayName("one household has exactly one September — the second attempt is 409")
    void oneHouseholdHasOneSeptember() {
        Session member = freshHousehold("budget-conflict");
        createPeriod(member, "2026-09-01", "5000.00", "CAD", "FLEXIBLE");

        HttpResponse<String> second = postJson(appUrl("/v1/budgets"),
            Json.object("month", "2026-09-30", "totalIncome", "6000.00", "currency", "CAD",
                "budgetType", "FLEXIBLE"),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(second.statusCode())
            .as("both bodies normalise to 2026-09-01, and under one shared ledger two members "
                + "must not each own a September. Body: %s", second.body())
            .isEqualTo(409);
        assertThat(Json.errorCode(second.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("the list is newest month first and is shared with every household mate")
    void theListIsSharedAndOrdered() {
        Session admin = freshHousehold("budget-list");
        createPeriod(admin, "2026-07-01", "1000.00", "CAD", "FLEXIBLE");
        createPeriod(admin, "2026-09-01", "3000.00", "CAD", "FLEXIBLE");
        createPeriod(admin, "2026-08-01", "2000.00", "CAD", "FLEXIBLE");
        Session mate = inviteAndRegister(admin, "budget-mate");

        List<JsonNode> asAdmin =
            Json.elements(Json.data(get(appUrl("/v1/budgets"), admin.accessToken()).body()));
        List<JsonNode> asMate =
            Json.elements(Json.data(get(appUrl("/v1/budgets"), mate.accessToken()).body()));

        assertThat(asAdmin.stream().map(row -> row.path("month").asString()))
            .containsExactly("2026-09-01", "2026-08-01", "2026-07-01");
        assertThat(asMate.stream().map(row -> row.path("id").asString()).toList())
            .as("one shared ledger per household: the mate sees the same three periods, and an "
                + "implementation scoped by user_id would answer 200 with an empty array here")
            .containsExactlyElementsOf(asAdmin.stream().map(row -> row.path("id").asString())
                .toList());
    }

    @Test
    @DisplayName("GET /v1/budgets/current answers this month, and 404 when there is none")
    void currentResolvesThisMonthOrAnswersNotFound() {
        Session member = freshHousehold("budget-current");

        HttpResponse<String> missing =
            get(appUrl("/v1/budgets/current"), member.accessToken());
        assertThat(missing.statusCode())
            .as("§4.6 is explicit: a month with no period is a 404, never an empty 200 that every "
                + "client would then have to interpret")
            .isEqualTo(404);

        // The caller's own month, from the caller's own timezone — not the test JVM's.
        String today = todayForCaller(member);
        YearMonth month = YearMonth.from(LocalDate.parse(today));
        createPeriod(member, month.atDay(1).toString(), "5000.00", "CAD", "FLEXIBLE");

        JsonNode current = Json.data(
            get(appUrl("/v1/budgets/current"), member.accessToken()).body());
        assertThat(current.path("month").asString()).isEqualTo(month.atDay(1).toString());
    }

    @Test
    @DisplayName("PUT replaces the income and the type; month and currency are not writable")
    void updateReplacesTheMutableFields() {
        Session member = freshHousehold("budget-update");
        String periodId = createPeriodId(member, "2026-09-01");

        JsonNode updated = Json.data(putJson(appUrl("/v1/budgets/" + periodId),
            Json.object("totalIncome", "6250.50", "budgetType", "ROLLOVER"),
            "Authorization", "Bearer " + member.accessToken()).body());

        assertThat(new BigDecimal(updated.path("totalIncome").asString()))
            .isEqualByComparingTo(new BigDecimal("6250.50"));
        assertThat(updated.path("budgetType").asString()).isEqualTo("ROLLOVER");
        assertThat(updated.path("month").asString())
            .as("the month is the period's identity inside the household and is not writable")
            .isEqualTo("2026-09-01");
        assertThat(updated.path("currency").asString())
            .as("re-denominating a period would silently reinterpret every envelope under it")
            .isEqualTo("CAD");
    }

    @Test
    @DisplayName("deleting a period takes its envelopes with it and leaves the ledger alone")
    void deletingAPeriodCascadesToEnvelopesOnly() {
        Session member = freshHousehold("budget-delete");
        String accountId = createManualAccount(member, "Chequing " + UUID.randomUUID());
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String periodId = createPeriodId(member, "2026-09-01");
        String envelopeId =
            createEnvelope(member, periodId, groceries, "400.00", "CAD").path("id").asString();
        JsonNode transaction = createManualTransaction(member, accountId, "40.00", "2026-09-05",
            "DEBIT", "GROCER");

        HttpResponse<String> deleted = delete(appUrl("/v1/budgets/" + periodId), null,
            "Authorization", "Bearer " + member.accessToken());
        assertThat(deleted.statusCode()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM envelope WHERE id = ?",
            Integer.class, UUID.fromString(envelopeId)))
            .as("V24's ON DELETE CASCADE: an envelope has no meaning outside the month it budgets")
            .isZero();
        assertThat(getTransaction(member, transaction.path("id").asString()).path("id").asString())
            .as("the transaction is untouched — an envelope is an aggregate over the ledger, "
                + "never a part of it")
            .isEqualTo(transaction.path("id").asString());
    }

    @Test
    @DisplayName("a period of another household is 404 on every by-id route, never 403")
    void aForeignPeriodIsNotFound() {
        Session owner = freshHousehold("budget-owner");
        Session outsider = freshHousehold("budget-outsider");
        String periodId = createPeriodId(owner, "2026-09-01");

        assertThat(get(appUrl("/v1/budgets/" + periodId), outsider.accessToken()).statusCode())
            .isEqualTo(404);
        assertThat(putJson(appUrl("/v1/budgets/" + periodId),
            Json.object("totalIncome", "1.00", "budgetType", "FLEXIBLE"),
            "Authorization", "Bearer " + outsider.accessToken()).statusCode()).isEqualTo(404);
        assertThat(delete(appUrl("/v1/budgets/" + periodId), null,
            "Authorization", "Bearer " + outsider.accessToken()).statusCode()).isEqualTo(404);

        assertThat(get(appUrl("/v1/budgets/" + periodId), owner.accessToken()).statusCode())
            .as("and the owner still reaches it, so the assertions above are not passing because "
                + "the route is broken for everybody")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("an unknown period id is 404")
    void anUnknownIdIsNotFound() {
        Session member = freshHousehold("budget-ids");

        assertThat(get(appUrl("/v1/budgets/" + unknownId()), member.accessToken()).statusCode())
            .as("a syntactically valid id that belongs to nothing is the same answer as one that "
                + "belongs to somebody else — that is what makes 404 the safe answer")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("a negative income is refused with a field error")
    void aNegativeIncomeIsRefused() {
        Session member = freshHousehold("budget-validation");

        // No opt-out: MoneyAmount's pattern admits a leading minus, so a negative amount is a
        // perfectly well-formed request that the SERVER refuses on @DecimalMin. That is the
        // division of labour the contract intends — shape in the schema, meaning in the server.
        HttpResponse<String> response = postJson(appUrl("/v1/budgets"),
            Json.object("month", "2026-09-01", "totalIncome", "-1.00", "currency", "CAD",
                "budgetType", "FLEXIBLE"),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }
}
