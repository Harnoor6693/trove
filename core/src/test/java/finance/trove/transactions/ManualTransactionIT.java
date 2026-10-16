package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Hand-entered transactions ({@code docs/recovery/07-READINESS-GAPS.md} P2-13).
 *
 * <p>Cash and cheques could not be recorded at all before this step: the transaction controller
 * exposed a GET and a PATCH of tags and notes, and {@code connected_account} required a
 * {@code source_plugin} and a NOT NULL {@code config_encrypted}, so a manual account could not even
 * be inserted.
 */
class ManualTransactionIT extends LedgerSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private finance.trove.categorisation.RecordingAiCategorisationTier aiTier;


    @Test
    @DisplayName("A transaction entered by hand is stored, marked manual, and categorised")
    void aManualTransactionIsStoredAndCategorised() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());

        JsonNode created = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "Blue Door Coffee");

        assertThat(created.path("manual").asBoolean())
            .as("manual is what stops a sync or an import undo from overwriting or removing it")
            .isTrue();
        assertThat(created.path("accountId").asString()).isEqualTo(accountId);
        assertThat(created.path("currency").asString())
            .as("the currency is the account's; the request has no field for one")
            .isEqualTo("CAD");
        assertThat(created.path("type").asString()).isEqualTo("DEBIT");
        assertThat(new BigDecimal(created.path("amount").asString()))
            .isEqualByComparingTo("4.75");
        assertThat(created.path("providerTransactionId").isNull())
            .as("a hand-entered row has no source and therefore no provider id")
            .isTrue();
        assertThat(created.path("pending").asBoolean()).isFalse();
        assertThat(created.path("categoryId").isNull())
            .as("§6 D4: transaction.category_id is the column nothing wrote before this step, and "
                + "the pipeline's last tier is the Uncategorised system category, so it is never "
                + "left null")
            .isFalse();
    }

    @Test
    @DisplayName("An omitted type is a DEBIT — money out is what a quick-add sheet records")
    void theTypeDefaultsToDebit() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());

        HttpResponse<String> response = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", accountId, "amount", "12.00", "date", "2026-09-04"),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(Json.data(response.body()).path("type").asString()).isEqualTo("DEBIT");
    }

    @Test
    @DisplayName("An explicit categoryId pins the choice and skips the pipeline")
    void anExplicitCategoryIsPinned() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String coffee = systemCategoryId(admin, "food.coffee");

        HttpResponse<String> response = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", accountId, "amount", "4.75", "date", "2026-09-04",
                "merchant", "Somewhere Unrecognised", "categoryId", coffee),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(201);
        String transactionId = Json.data(response.body()).path("id").asString();
        assertThat(Json.data(response.body()).path("categoryId").asString()).isEqualTo(coffee);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM category_override WHERE transaction_id = ?", Integer.class,
            UUID.fromString(transactionId)))
            .as("a category chosen at entry time is a decision, so it is pinned and a later "
                + "automatic categorisation cannot undo it")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("A category another household owns is 400, not 404 or a silent write")
    void anInvisibleCategoryIsRejected() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());

        HttpResponse<String> response = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", accountId, "amount", "4.75", "date", "2026-09-04",
                "categoryId", unknownId().toString()),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("A hand-entered transaction is refused on a non-MANUAL account")
    void manualEntryIsRefusedOnASyncedAccount() {
        Session admin = loginBootstrapAdmin();
        String syncable = createSyncableAccount(admin, "Bank " + UUID.randomUUID());

        HttpResponse<String> response = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", syncable, "amount", "4.75", "date", "2026-09-04"),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode())
            .as("a hand-entered row on a synced account is indistinguishable from a fetched one, "
                + "so the next sync would eventually reconcile it away")
            .isEqualTo(409);
        assertThat(Json.errorCode(response.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("An account of another household is 404, never 403")
    void aForeignAccountIsNotFound() {
        Session admin = loginBootstrapAdmin();

        HttpResponse<String> response = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", unknownId().toString(), "amount", "4.75",
                "date", "2026-09-04"),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(Json.errorCode(response.body())).isEqualTo("not-found");
    }

    @Test
    @DisplayName("A zero or negative amount is refused — direction is the type, not the sign")
    void nonPositiveAmountsAreRejected() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String bearer = "Bearer " + admin.accessToken();

        // "0" is a syntactically valid decimal, so it conforms to the contract and needs no
        // opt-out: it is the SERVER's @DecimalMin that refuses it, which is exactly the assertion.
        HttpResponse<String> zero = postJson(appUrl("/v1/transactions"),
            Json.object("accountId", accountId, "amount", "0", "date", "2026-09-04"),
            "Authorization", bearer);
        assertThat(zero.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(zero.body())).isEqualTo("validation-error");

        HttpResponse<String> negative = withoutRequestValidation(
            "the contract's amount pattern has no sign, so a conforming client cannot send a "
                + "negative amount at all; this asserts the SERVER refuses one too rather than "
                + "trusting the document",
            () -> postJson(appUrl("/v1/transactions"),
                Json.object("accountId", accountId, "amount", "-4.75", "date", "2026-09-04"),
                "Authorization", bearer));
        assertThat(negative.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("B1-14: an amount finer than a cent is refused, not rounded to 0.00 or 12.35")
    void subCentAmountsAreRejected() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        for (String amount : new String[] {"0.004", "12.345"}) {
            HttpResponse<String> response = withoutRequestValidation(
                "the contract now says at most two decimals; this asserts the SERVER refuses a "
                    + "finer amount too rather than trusting the document",
                () -> postJson(appUrl("/v1/transactions"),
                    Json.object("accountId", accountId, "amount", amount, "date", "2026-09-04"),
                    "Authorization", "Bearer " + admin.accessToken()));
            assertThat(response.statusCode()).as(amount).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("B1-12: the AI model call for a hand-entered row runs outside any DB transaction")
    void theAiCallHoldsNoDatabaseTransaction() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        aiTier.reset();

        createManualTransaction(admin, accountId, "5.00", "2026-09-04", "DEBIT",
            "UNKNOWN SHOP " + UUID.randomUUID());

        assertThat(aiTier.calls()).as("the AI tier was reached").isPositive();
        assertThat(aiTier.callsInsideTransaction())
            .as("a slow model call inside a transaction pins a pooled connection for its length")
            .isZero();
    }

    @Test
    @DisplayName("A hand-entered transaction can be deleted; a synced one cannot")
    void onlyManualRowsAreDeletable() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Cash " + UUID.randomUUID());
        String manualId = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "Coffee").path("id").asString();

        assertThat(delete(appUrl("/v1/transactions/" + manualId), null,
            "Authorization", "Bearer " + admin.accessToken()).statusCode()).isEqualTo(200);
        assertThat(get(appUrl("/v1/transactions/" + manualId), admin.accessToken()).statusCode())
            .isEqualTo(404);

        String syncable = createSyncableAccount(admin, "Bank " + UUID.randomUUID());
        UUID syncedRow = insertSyncedRow(admin, syncable);
        HttpResponse<String> refused = delete(appUrl("/v1/transactions/" + syncedRow), null,
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(refused.statusCode())
            .as("a fetched row deleted on its own would simply be fetched again")
            .isEqualTo(409);
        assertThat(Json.errorCode(refused.body())).isEqualTo("conflict");
    }

    /**
     * Inserts a row that looks like one a source produced.
     *
     * <p>The only fixture in this file that does not go through an endpoint, because there is no
     * endpoint that creates a non-manual transaction in this step: importing is collapse step 7's,
     * and {@code SyncOrchestratorIT} covers the fetch path.
     */
    private UUID insertSyncedRow(Session session, String accountId) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO transaction (household_id, account_id, user_id, "
                + "provider_transaction_id, amount, currency, type, date, pending, manual) "
                + "VALUES (?, ?, ?, ?, 25.0000, 'CAD', 'DEBIT', DATE '2026-09-04', false, false) "
                + "RETURNING id",
            UUID.class, session.householdId(), UUID.fromString(accountId), session.userId(),
            "synced-" + UUID.randomUUID());
    }
}
