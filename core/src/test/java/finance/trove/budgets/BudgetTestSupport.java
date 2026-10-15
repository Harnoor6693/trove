package finance.trove.budgets;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.households.invites.InviteService;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The shared setup for collapse step 8's integration tests.
 *
 * <p><strong>Every budget test needs its own household</strong>, which is not a preference:
 * {@code UNIQUE (household_id, month)} means one household has exactly one September, so two tests
 * that both opened September in the suite-wide bootstrap household would fight over it and the
 * second would see a 409 that has nothing to do with what it was asserting. Creating a household
 * with two SQL statements and then joining it through the real registration endpoint is the
 * pattern {@code AccessControlIT#createSecondHousehold} and {@code NetWorthTestSupport} both use;
 * it is repeated here rather than extracted because it is a handful of lines and steps 9 and 10
 * are appending to their own packages in parallel.
 *
 * <p>Abstract and without the {@code IT} suffix, so Failsafe does not try to run it.
 */
public abstract class BudgetTestSupport extends LedgerSupport {

    /** The slug of a system category every household can budget, from the V14 seed. */
    protected static final String GROCERIES_SLUG = "food.grocery";

    /** A second one, so a test can move spend between two envelopes. */
    protected static final String RESTAURANTS_SLUG = "food.restaurants";

    /** Where an ingested row lands when no rule matches it — {@code TransactionCategoriser}. */
    protected static final String UNCATEGORISED_SLUG = "system.uncategorised";

    private final SecureRandom random = new SecureRandom();

    /** A brand-new household with one ADMIN member, joined through the real endpoints. */
    protected Session freshHousehold(String memberPrefix) {
        UUID householdId = jdbcTemplate.queryForObject(
            "INSERT INTO households (name, registration_open) VALUES (?, false) RETURNING id",
            UUID.class, "Budget test household " + UUID.randomUUID());

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbcTemplate.update(
            "INSERT INTO household_invite (household_id, role, token_hash, expires_at) "
                + "VALUES (?, 'ADMIN', ?, now() + interval '72 hours')",
            householdId, InviteService.hash(token));

        return registerWithInvite(token, uniqueEmail(memberPrefix), MEMBER_PASSWORD);
    }

    /** Opens a budget period through the real endpoint and returns the created row. */
    protected JsonNode createPeriod(Session session, String month, String totalIncome,
                                    String currency, String budgetType) {
        HttpResponse<String> response = postJson(appUrl("/v1/budgets"),
            Json.object("month", month, "totalIncome", totalIncome, "currency", currency,
                "budgetType", budgetType),
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("opening a budget period failed: %s", response.body()).isEqualTo(201);
        return Json.data(response.body());
    }

    /** A FLEXIBLE CAD period, which is what most tests want. */
    protected String createPeriodId(Session session, String month) {
        return createPeriod(session, month, "5000.00", "CAD", "FLEXIBLE").path("id").asString();
    }

    /** Budgets a category inside a period and returns the created envelope. */
    protected JsonNode createEnvelope(Session session, String periodId, String categoryId,
                                      String allocated, String currency) {
        HttpResponse<String> response = postJson(appUrl("/v1/budgets/" + periodId + "/envelopes"),
            Json.object("categoryId", categoryId, "allocated", allocated, "currency", currency),
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("creating an envelope failed: %s", response.body()).isEqualTo(201);
        return Json.data(response.body());
    }

    /** One envelope as the caller sees it now. */
    protected JsonNode readEnvelope(Session session, String periodId, String envelopeId) {
        HttpResponse<String> response = get(
            appUrl("/v1/budgets/" + periodId + "/envelopes/" + envelopeId), session.accessToken());
        assertThat(response.statusCode())
            .as("reading the envelope failed: %s", response.body()).isEqualTo(200);
        return Json.data(response.body());
    }

    /** Every envelope of a period. */
    protected List<JsonNode> listEnvelopes(Session session, String periodId) {
        HttpResponse<String> response =
            get(appUrl("/v1/budgets/" + periodId + "/envelopes"), session.accessToken());
        assertThat(response.statusCode())
            .as("listing envelopes failed: %s", response.body()).isEqualTo(200);
        return Json.elements(Json.data(response.body()));
    }

    /** Starts a savings goal through the real endpoint and returns the created row. */
    protected JsonNode createGoal(Session session, String name, String targetAmount,
                                  String currency, String deadline) {
        HttpResponse<String> response = postJson(appUrl("/v1/goals"),
            Json.object("name", name, "targetAmount", targetAmount, "currency", currency,
                "deadline", deadline),
            "Authorization", "Bearer " + session.accessToken());
        assertThat(response.statusCode())
            .as("creating a goal failed: %s", response.body()).isEqualTo(201);
        return Json.data(response.body());
    }

    /**
     * What the database says an envelope's {@code spent} is — the figure every "equals the SQL
     * sum" assertion is measured against, read straight out of Postgres rather than off the wire.
     */
    protected java.math.BigDecimal storedSpent(String envelopeId) {
        return jdbcTemplate.queryForObject("SELECT spent FROM envelope WHERE id = ?",
            java.math.BigDecimal.class, UUID.fromString(envelopeId));
    }

    /**
     * The same aggregate computed independently in SQL: {@code DEBIT} less {@code REFUND}, this
     * category, this month, this currency, transfer legs excluded.
     *
     * <p>Written out here on purpose. An assertion that compared the service's answer with the
     * service's own query would pass whatever the rule was; this is the second opinion §9 risk 4
     * asks for.
     */
    protected java.math.BigDecimal sqlSpend(UUID householdId, String categoryId, String month,
                                            String currency) {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE t.type WHEN 'DEBIT' THEN t.amount "
                + "WHEN 'REFUND' THEN -t.amount ELSE 0 END), 0) FROM transaction t "
                + "WHERE t.household_id = ? AND t.category_id = ? AND t.currency = ? "
                + "AND t.transfer_group_id IS NULL "
                + "AND t.date >= date_trunc('month', ?::date) "
                + "AND t.date < date_trunc('month', ?::date) + interval '1 month'",
            java.math.BigDecimal.class, householdId, UUID.fromString(categoryId), currency,
            month, month);
    }
}
