package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The categorisation write path ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D4, §3 V21).
 *
 * <p>The assertion that matters most is that a {@code PATCH} writes <strong>both</strong>
 * {@code transaction.category_id} and the {@code category_override} pin. §6 D4 names writing only
 * the override table as the defect: a budget aggregate reads the column, so a correction that
 * touched only the pin would move no envelope spend at all — and the mistake is invisible until
 * somebody notices their budget never changes.
 */
class CategoryOverrideIT extends LedgerSupport {

    private Session admin;
    private String accountId;
    private String transactionId;

    @BeforeEach
    void setUpATransaction() {
        admin = loginBootstrapAdmin();
        accountId = createManualAccount(admin, "Override " + UUID.randomUUID());
        transactionId = createManualTransaction(admin, accountId, "4.75", "2026-09-04", "DEBIT",
            "An Unrecognised Merchant " + UUID.randomUUID()).path("id").asString();
    }

    @Test
    @DisplayName("A new row is categorised on insert, never left without a category")
    void everyRowIsCategorisedOnInsert() {
        assertThat(getTransaction(admin, transactionId).path("categoryId").isNull())
            .as("§6 D4: the pipeline's last tier is the Uncategorised system category, resolved "
                + "by SLUG so a rename of the seed cannot break it")
            .isFalse();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT c.slug FROM transaction t JOIN category c ON c.id = t.category_id "
                + "WHERE t.id = ?", String.class, UUID.fromString(transactionId)))
            .isEqualTo("system.uncategorised");
    }

    @Test
    @DisplayName("PATCH writes BOTH transaction.category_id and the category_override pin")
    void aCorrectionWritesTheColumnAndThePin() {
        String coffee = systemCategoryId(admin, "food.coffee");

        HttpResponse<String> response = patchJson(appUrl("/v1/transactions/" + transactionId),
            Json.object("categoryId", coffee), "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.data(response.body()).path("categoryId").asString()).isEqualTo(coffee);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT category_id FROM transaction WHERE id = ?", UUID.class,
            UUID.fromString(transactionId)))
            .as("the column is what every budget aggregate reads; writing only the pin was the "
                + "named D4 defect")
            .isEqualTo(UUID.fromString(coffee));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT category_id FROM category_override WHERE transaction_id = ?", UUID.class,
            UUID.fromString(transactionId)))
            .as("the pin is what survives the next re-sync")
            .isEqualTo(UUID.fromString(coffee));
    }

    @Test
    @DisplayName("Correcting twice replaces the pin rather than accumulating contradictory rows")
    void thePinIsUpserted() {
        String bearer = "Bearer " + admin.accessToken();
        String coffee = systemCategoryId(admin, "food.coffee");
        String groceries = systemCategoryId(admin, "food.grocery");

        patchJson(appUrl("/v1/transactions/" + transactionId), Json.object("categoryId", coffee),
            "Authorization", bearer);
        patchJson(appUrl("/v1/transactions/" + transactionId),
            Json.object("categoryId", groceries), "Authorization", bearer);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM category_override WHERE transaction_id = ?", Integer.class,
            UUID.fromString(transactionId)))
            .as("V21 is UNIQUE (transaction_id): one pin per transaction, unlike the "
                + "pre-collapse UNIQUE (user_id, transaction_id), which let two members of one "
                + "household hold contradictory pins on a ledger they share")
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT category_id FROM category_override WHERE transaction_id = ?", UUID.class,
            UUID.fromString(transactionId)))
            .isEqualTo(UUID.fromString(groceries));
    }

    @Test
    @DisplayName("Clearing the category removes the pin too")
    void clearingTheCategoryRemovesThePin() {
        String bearer = "Bearer " + admin.accessToken();
        patchJson(appUrl("/v1/transactions/" + transactionId),
            Json.object("categoryId", systemCategoryId(admin, "food.coffee")),
            "Authorization", bearer);

        assertThat(patchJson(appUrl("/v1/transactions/" + transactionId),
            Json.object("categoryId", null), "Authorization", bearer).statusCode()).isEqualTo(200);

        assertThat(getTransaction(admin, transactionId).path("categoryId").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM category_override WHERE transaction_id = ?", Integer.class,
            UUID.fromString(transactionId))).isZero();
    }

    @Test
    @DisplayName("A household mate sees the same pinned category — the pin is the ledger's")
    void thePinIsSharedAcrossTheHousehold() {
        Session mate = inviteAndRegister(admin, "override-mate");
        String coffee = systemCategoryId(admin, "food.coffee");
        patchJson(appUrl("/v1/transactions/" + transactionId), Json.object("categoryId", coffee),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(getTransaction(mate, transactionId).path("categoryId").asString())
            .as("household_id is the scope; user_id only records who pinned it")
            .isEqualTo(coffee);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT household_id FROM category_override WHERE transaction_id = ?", UUID.class,
            UUID.fromString(transactionId)))
            .isEqualTo(admin.householdId());
    }

    @Test
    @DisplayName("A category the household cannot see is 400 and changes nothing")
    void anInvisibleCategoryIsRefused() {
        UUID before = jdbcTemplate.queryForObject(
            "SELECT category_id FROM transaction WHERE id = ?", UUID.class,
            UUID.fromString(transactionId));

        HttpResponse<String> response = patchJson(appUrl("/v1/transactions/" + transactionId),
            Json.object("categoryId", unknownId().toString()),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT category_id FROM transaction WHERE id = ?", UUID.class,
            UUID.fromString(transactionId))).isEqualTo(before);
    }

    @Test
    @DisplayName("Deleting the transaction takes its pin with it")
    void thePinCascadesWithTheTransaction() {
        patchJson(appUrl("/v1/transactions/" + transactionId),
            Json.object("categoryId", systemCategoryId(admin, "food.coffee")),
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(delete(appUrl("/v1/transactions/" + transactionId), null,
            "Authorization", "Bearer " + admin.accessToken()).statusCode()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM category_override WHERE transaction_id = ?", Integer.class,
            UUID.fromString(transactionId)))
            .as("V21's ON DELETE CASCADE — a pin on a deleted transaction is evidence of nothing")
            .isZero();
    }
}
