package finance.trove.budgets;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.TestBankSource;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The seam that retires finding D1, end to end
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5, §6 D1; §9 risk 4).
 *
 * <p>Section 2.5 and risk 4 both name this class and both name the same shape: ingest,
 * recategorise, re-run, and {@code spent} equals the SQL sum each time. The SQL sum is computed
 * independently in {@link BudgetTestSupport#sqlSpend} rather than by asking the service what it
 * thinks — an assertion measured against the implementation's own query would pass whatever the
 * rule was.
 *
 * <p>The pre-collapse platform could not have passed a single test in this class: nothing anywhere
 * wrote {@code Envelope.spent}, so every figure below would read {@code 0.0000}.
 */
class EnvelopeSpendServiceIT extends BudgetTestSupport {

    @Autowired
    private TestBankSource source;

    @Autowired
    private finance.trove.transactions.TransferPairingJob pairingJob;

    private Session member;
    private String accountId;
    private String periodId;
    private String groceries;
    private String restaurants;
    private String envelopeId;

    @BeforeEach
    void setUpAHouseholdWithABudget() {
        source.reset();
        member = freshHousehold("spend");
        accountId = createManualAccount(member, "Chequing " + UUID.randomUUID());
        periodId = createPeriodId(member, "2026-09-01");
        groceries = systemCategoryId(member, GROCERIES_SLUG);
        restaurants = systemCategoryId(member, RESTAURANTS_SLUG);
        envelopeId = createEnvelope(member, periodId, groceries, "400.00", "CAD")
            .path("id").asString();
    }

    @AfterEach
    void releaseTheSource() {
        source.releaseFetch();
        source.reset();
    }

    /** Records a transaction and pins it to a category, which is what publishes the event. */
    private String spend(String amount, String date, String type, String categoryId) {
        JsonNode created = createManualTransaction(member, accountId, amount, date, type,
            "MERCHANT " + UUID.randomUUID());
        String id = created.path("id").asString();
        HttpResponse<String> patched = patchJson(appUrl("/v1/transactions/" + id),
            Json.object("categoryId", categoryId),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
        return id;
    }

    private void assertSpentEqualsTheSqlSum() {
        assertThat(storedSpent(envelopeId))
            .as("the envelope's stored figure must equal an independently written SQL aggregate "
                + "over the same rows — §9 risk 4's whole assertion")
            .isEqualByComparingTo(sqlSpend(member.householdId(), groceries, "2026-09-01", "CAD"));
    }

    @Test
    @DisplayName("categorising transactions writes the envelope's spent — D1's fix, end to end")
    void categorisingWritesSpent() {
        spend("30.00", "2026-09-02", "DEBIT", groceries);
        spend("45.50", "2026-09-09", "DEBIT", groceries);
        spend("24.50", "2026-09-20", "DEBIT", groceries);

        assertThat(storedSpent(envelopeId))
            .as("the pre-collapse platform read 0.0000 here, for every envelope, for ever")
            .isEqualByComparingTo(new BigDecimal("100.0000"));
        assertSpentEqualsTheSqlSum();
        assertThat(readEnvelope(member, periodId, envelopeId).path("spent").asString())
            .isEqualTo("100.0000");
    }

    @Test
    @DisplayName("B2-04: deleting a categorised manual transaction takes it out of spent")
    void deletingATransactionRecomputesSpent() {
        String id = spend("60.00", "2026-09-05", "DEBIT", groceries);
        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("60.0000"));

        HttpResponse<String> deleted = exchange("DELETE", appUrl("/v1/transactions/" + id), null,
            "Authorization", "Bearer " + member.accessToken());
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);

        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(BigDecimal.ZERO);
        assertSpentEqualsTheSqlSum();
    }

    @Test
    @DisplayName("B2-04: pairing a transfer, by the job or by PATCH, takes the leg out of spent")
    void transferPairingRecomputesSpent() {
        String other = createManualAccount(member, "Card " + UUID.randomUUID());
        String debit = createManualTransaction(member, accountId, "60.00", "2026-09-25", "DEBIT",
            "CARD PAYMENT " + UUID.randomUUID()).path("id").asString();
        patchJson(appUrl("/v1/transactions/" + debit), Json.object("categoryId", groceries),
            "Authorization", "Bearer " + member.accessToken());
        createManualTransaction(member, other, "60.00", "2026-09-26", "CREDIT",
            "PAYMENT RECEIVED " + UUID.randomUUID());
        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("60.0000"));

        pairingJob.runOnce();

        assertThat(storedSpent(envelopeId))
            .as("a paired leg is a transfer, not spending")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertSpentEqualsTheSqlSum();

        HttpResponse<String> unlinked = patchJson(appUrl("/v1/transactions/" + debit),
            "{\"transferGroupId\":null}", "Authorization", "Bearer " + member.accessToken());
        assertThat(unlinked.statusCode()).as(unlinked.body()).isEqualTo(200);
        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("60.0000"));
        assertSpentEqualsTheSqlSum();
    }

    @Test
    @DisplayName("recategorising moves the spend off one envelope and onto the other")
    void recategorisingMovesTheSpend() {
        String restaurantsEnvelope =
            createEnvelope(member, periodId, restaurants, "200.00", "CAD").path("id").asString();
        String transactionId = spend("60.00", "2026-09-05", "DEBIT", groceries);
        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("60.0000"));

        patchJson(appUrl("/v1/transactions/" + transactionId),
            Json.object("categoryId", restaurants),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(storedSpent(envelopeId))
            .as("the event carries previousCategoryId precisely so the envelope the transaction "
                + "LEFT is recomputed too — otherwise it would count it for ever")
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(storedSpent(restaurantsEnvelope))
            .isEqualByComparingTo(new BigDecimal("60.0000"));
        assertSpentEqualsTheSqlSum();
    }

    @Test
    @DisplayName("risk 4: recategorising the same transaction back and forth cannot double count")
    void movingATransactionBackAndForthCannotDoubleCount() {
        createEnvelope(member, periodId, restaurants, "200.00", "CAD");
        String transactionId = spend("75.00", "2026-09-07", "DEBIT", groceries);

        for (int i = 0; i < 3; i++) {
            patchJson(appUrl("/v1/transactions/" + transactionId),
                Json.object("categoryId", restaurants),
                "Authorization", "Bearer " + member.accessToken());
            patchJson(appUrl("/v1/transactions/" + transactionId),
                Json.object("categoryId", groceries),
                "Authorization", "Bearer " + member.accessToken());
        }

        assertThat(storedSpent(envelopeId))
            .as("an incrementing writer would read 525.0000 after seven passes; a rewrite reads "
                + "the sum, every time")
            .isEqualByComparingTo(new BigDecimal("75.0000"));
        assertSpentEqualsTheSqlSum();
    }

    @Test
    @DisplayName("a REFUND nets down, exactly as the dashboard's rule says")
    void aRefundNetsDown() {
        spend("100.00", "2026-09-03", "DEBIT", groceries);
        spend("30.00", "2026-09-11", "REFUND", groceries);

        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(new BigDecimal("70.0000"));
        assertSpentEqualsTheSqlSum();

        JsonNode dashboard = Json.data(
            get(appUrl("/v1/dashboard/today"), member.accessToken()).body());
        assertThat(dashboard.path("byCurrency").isArray())
            .as("and the dashboard is reading the same ledger under the same rule — the two "
                + "screens are specified to agree about whether a refund reduces spending")
            .isTrue();
    }

    @Test
    @DisplayName("gap P2-14: a transfer leg is invisible to the envelope it would otherwise land "
        + "in")
    void transferLegsAreExcluded() {
        spend("100.00", "2026-09-10", "DEBIT", groceries);
        String creditCardPayment = spend("500.00", "2026-09-12", "DEBIT", groceries);
        assertThat(storedSpent(envelopeId))
            .as("before pairing, both rows look like ordinary spending")
            .isEqualByComparingTo(new BigDecimal("600.0000"));

        // Pair the leg the way TransferPairingService does. Writing the column directly is
        // deliberate: pairing needs two accounts and an opposite leg, and none of that is what
        // this test is about — what matters is that a paired leg stops counting.
        jdbcTemplate.update("UPDATE transaction SET transfer_group_id = ? WHERE id = ?",
            UUID.randomUUID(), UUID.fromString(creditCardPayment));

        // Something has to make the aggregate recompute. A third transaction landing in the same
        // envelope is the ordinary way that happens, and it exercises the real listener rather
        // than reaching into the service.
        spend("25.00", "2026-09-13", "DEBIT", groceries);

        assertThat(storedSpent(envelopeId))
            .as("100 + 25, and NOT the 500: paying a credit card from chequing moves money "
                + "between two accounts the household already owns, so it is neither spending nor "
                + "income. The exclusion is inherited from "
                + "TransactionQueryService.findSpendableInRange rather than re-derived here")
            .isEqualByComparingTo(new BigDecimal("125.0000"));
        assertSpentEqualsTheSqlSum();
    }

    @Test
    @DisplayName("a USD transaction is invisible to a CAD envelope, and vice versa")
    void aCurrencyMismatchIsInvisible() {
        String usdAccount = createAccount(member, MANUAL, "US Chequing " + UUID.randomUUID(),
            "CHECKING", "USD");
        String canadianEnvelope = envelopeId;
        String americanPeriod = createPeriod(member, "2026-10-01", "5000.00", "USD", "FLEXIBLE")
            .path("id").asString();
        String americanEnvelope =
            createEnvelope(member, americanPeriod, groceries, "400.00", "USD")
                .path("id").asString();

        JsonNode usdRow = createManualTransaction(member, usdAccount, "80.00", "2026-10-04",
            "DEBIT", "US GROCER " + UUID.randomUUID());
        patchJson(appUrl("/v1/transactions/" + usdRow.path("id").asString()),
            Json.object("categoryId", groceries),
            "Authorization", "Bearer " + member.accessToken());
        spend("20.00", "2026-09-04", "DEBIT", groceries);

        assertThat(storedSpent(canadianEnvelope))
            .as("there is no FX rate anywhere in this system: 80 USD is not part of a CAD budget")
            .isEqualByComparingTo(new BigDecimal("20.0000"));
        assertThat(storedSpent(americanEnvelope))
            .as("a household budgeting one month in CAD and another in USD is a normal case")
            .isEqualByComparingTo(new BigDecimal("80.0000"));
    }

    @Test
    @DisplayName("a fresh sync moves the envelope: the ingest listener, not just the PATCH one")
    void ingestionAloneMovesAnEnvelope() {
        // The plan wires D1 to TransactionCategorisedEvent alone. On the merged code that event
        // has exactly ONE publisher — the PATCH path — while ingested rows are categorised at
        // INSERT with no event at all, so a budget wired only to it would sit at zero through a
        // whole freshly synced month. This asserts the second listener closes that.
        String syncable = createSyncableAccount(member, "Bank " + UUID.randomUUID());
        String uncategorised = systemCategoryId(member, UNCATEGORISED_SLUG);
        String uncategorisedEnvelope =
            createEnvelope(member, periodId, uncategorised, "900.00", "CAD").path("id").asString();
        source.returning(List.of(
            row("sync-1", "40.00", LocalDate.of(2026, 9, 14)),
            row("sync-2", "60.00", LocalDate.of(2026, 9, 15))));

        HttpResponse<String> synced = postJson(appUrl("/v1/accounts/" + syncable + "/sync"), null,
            "Authorization", "Bearer " + member.accessToken());
        assertThat(synced.statusCode()).as(synced.body()).isEqualTo(200);

        assertThat(storedSpent(uncategorisedEnvelope))
            .as("no member touched a category here; only the ingest listener could have moved it")
            .isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(storedSpent(uncategorisedEnvelope))
            .isEqualByComparingTo(
                sqlSpend(member.householdId(), uncategorised, "2026-09-01", "CAD"));
    }

    @Test
    @DisplayName("re-running the same sync changes nothing — the O7/M0 shape, from the budget's "
        + "side")
    void aSecondSyncOfTheSameRowsChangesNothing() {
        String syncable = createSyncableAccount(member, "Bank " + UUID.randomUUID());
        String uncategorised = systemCategoryId(member, UNCATEGORISED_SLUG);
        String uncategorisedEnvelope =
            createEnvelope(member, periodId, uncategorised, "900.00", "CAD").path("id").asString();
        source.returning(List.of(
            row("sync-1", "40.00", LocalDate.of(2026, 9, 14)),
            row("sync-2", "60.00", LocalDate.of(2026, 9, 15))));

        postJson(appUrl("/v1/accounts/" + syncable + "/sync"), null,
            "Authorization", "Bearer " + member.accessToken());
        BigDecimal afterFirst = storedSpent(uncategorisedEnvelope);
        postJson(appUrl("/v1/accounts/" + syncable + "/sync"), null,
            "Authorization", "Bearer " + member.accessToken());
        postJson(appUrl("/v1/accounts/" + syncable + "/sync"), null,
            "Authorization", "Bearer " + member.accessToken());

        assertThat(afterFirst).isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(storedSpent(uncategorisedEnvelope))
            .as("three syncs of the same file, one answer — because the aggregate is rewritten "
                + "from the ledger rather than added to")
            .isEqualByComparingTo(new BigDecimal("100.0000"));
        assertThat(storedSpent(uncategorisedEnvelope))
            .isEqualByComparingTo(
                sqlSpend(member.householdId(), uncategorised, "2026-09-01", "CAD"));
    }

    @Test
    @DisplayName("a month with no envelope for the category writes nothing and invents nothing")
    void aCategoryNobodyBudgetsIsANoOp() {
        spend("42.00", "2026-09-08", "DEBIT", restaurants);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM envelope WHERE period_id = ? AND category_id = ?",
            Integer.class, UUID.fromString(periodId), UUID.fromString(restaurants)))
            .as("creating an envelope here would invent a budget line the household never asked "
                + "for")
            .isZero();
        assertThat(storedSpent(envelopeId)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static RawTransaction row(String providerId, String amount, LocalDate date) {
        return RawTransaction.of(providerId, "external-1", new BigDecimal(amount), "CAD",
            "Sync row", "SYNCED MERCHANT", date, Instant.now(), TransactionType.DEBIT, false);
    }
}
