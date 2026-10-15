package finance.trove.budgets;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code /v1/budgets/{id}/envelopes} end to end ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6,
 * §6 D1).
 */
class EnvelopeIT extends BudgetTestSupport {

    @Test
    @DisplayName("an envelope carries its category's CURRENT name, joined rather than copied")
    void theCategoryNameIsJoinedOnRead() {
        Session member = freshHousehold("envelope-name");
        String periodId = createPeriodId(member, "2026-09-01");
        // A household category, so the test can rename it — system categories are read-only.
        String categoryId = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "Weekly Shop " + UUID.randomUUID(), "icon", null),
            "Authorization", "Bearer " + member.accessToken()).body()).path("id").asString();

        String envelopeId =
            createEnvelope(member, periodId, categoryId, "400.00", "CAD").path("id").asString();
        putJson(appUrl("/v1/categories/" + categoryId),
            Json.object("name", "Groceries And Household", "icon", null),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(readEnvelope(member, periodId, envelopeId).path("categoryName").asString())
            .as("the pre-collapse table stored a copy of the name taken at creation time, so a "
                + "rename left every envelope showing the old label for ever")
            .isEqualTo("Groceries And Household");
    }

    @Test
    @DisplayName("an envelope's currency defaults to the period's, not to a literal")
    void currencyDefaultsToThePeriod() {
        Session member = freshHousehold("envelope-currency");
        String periodId = createPeriod(member, "2026-09-01", "5000.00", "USD", "FLEXIBLE")
            .path("id").asString();
        String groceries = systemCategoryId(member, GROCERIES_SLUG);

        HttpResponse<String> response = postJson(appUrl("/v1/budgets/" + periodId + "/envelopes"),
            Json.object("categoryId", groceries, "allocated", "400.00", "currency", null),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(Json.data(response.body()).path("currency").asString())
            .as("an envelope lives inside a period, so the period is 'the row' its currency "
                + "comes from — never the CAD literal the source defaulted to")
            .isEqualTo("USD");
    }

    @Test
    @DisplayName("one envelope per category per period: the second is 409")
    void oneEnvelopePerCategory() {
        Session member = freshHousehold("envelope-conflict");
        String periodId = createPeriodId(member, "2026-09-01");
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        createEnvelope(member, periodId, groceries, "400.00", "CAD");

        HttpResponse<String> second = postJson(appUrl("/v1/budgets/" + periodId + "/envelopes"),
            Json.object("categoryId", groceries, "allocated", "100.00", "currency", "CAD"),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(second.statusCode())
            .as("two envelopes for one category would each be rewritten to the same ledger total "
                + "and the period would double-count itself. Body: %s", second.body())
            .isEqualTo(409);
        assertThat(Json.errorCode(second.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("a category this household cannot use is 400, not 404")
    void anInvisibleCategoryIsAValidationError() {
        Session member = freshHousehold("envelope-category");
        Session other = freshHousehold("envelope-category-other");
        String periodId = createPeriodId(member, "2026-09-01");
        String foreignCategory = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "Somebody Else's " + UUID.randomUUID(), "icon", null),
            "Authorization", "Bearer " + other.accessToken()).body()).path("id").asString();

        HttpResponse<String> response = postJson(appUrl("/v1/budgets/" + periodId + "/envelopes"),
            Json.object("categoryId", foreignCategory, "allocated", "100.00", "currency", "CAD"),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode())
            .as("the id arrived in a body as a field value, so fieldErrors can name it and a 404 "
                + "would be ambiguous with the period's own id being unknown. Body: %s",
                response.body())
            .isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("a category an envelope budgets cannot be deleted: 409, and never a 500")
    void anEnvelopeProtectsItsCategory() {
        Session member = freshHousehold("envelope-restrict");
        String periodId = createPeriodId(member, "2026-09-01");
        String categoryId = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "Protected " + UUID.randomUUID(), "icon", null),
            "Authorization", "Bearer " + member.accessToken()).body()).path("id").asString();
        createEnvelope(member, periodId, categoryId, "50.00", "CAD");

        HttpResponse<String> refused = delete(appUrl("/v1/categories/" + categoryId), null,
            "Authorization", "Bearer " + member.accessToken());

        assertThat(refused.statusCode())
            .as("V24 declares envelope.category_id ON DELETE RESTRICT, the choice step 5 already "
                + "made for merchant_category_map. GlobalExceptionHandler maps the resulting "
                + "DataIntegrityViolationException to 409, so step 5's delete path still answers "
                + "a conflict now that a second table restricts it. Body: %s", refused.body())
            .isEqualTo(409);
        assertThat(Json.errorCode(refused.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("ZERO_BASED: allocations must sum to the income, and a breach is 400 not 422")
    void theZeroBasedInvariantIsAValidationError() {
        Session member = freshHousehold("envelope-zerobased");
        String periodId = createPeriod(member, "2026-09-01", "1000.00", "CAD", "ZERO_BASED")
            .path("id").asString();
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String restaurants = systemCategoryId(member, RESTAURANTS_SLUG);

        HttpResponse<String> short_ = postJson(appUrl("/v1/budgets/" + periodId + "/envelopes"),
            Json.object("categoryId", groceries, "allocated", "400.00", "currency", "CAD"),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(short_.statusCode())
            .as("the source answered 422 here, a status this contract has no slug for at all")
            .isEqualTo(400);
        assertThat(Json.errorCode(short_.body())).isEqualTo("validation-error");

        // Allocate every dollar, and it is accepted.
        createEnvelope(member, periodId, groceries, "1000.00", "CAD");

        HttpResponse<String> wouldOverAllocate =
            postJson(appUrl("/v1/budgets/" + periodId + "/envelopes"),
                Json.object("categoryId", restaurants, "allocated", "1.00", "currency", "CAD"),
                "Authorization", "Bearer " + member.accessToken());
        assertThat(wouldOverAllocate.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("ZERO_BASED: a foreign-currency envelope cannot help balance the period (B2-06)")
    void zeroBasedIsCheckedPerCurrency() {
        Session member = freshHousehold("envelope-zerobased-ccy");
        String periodId = createPeriod(member, "2026-09-01", "1000.00", "CAD", "ZERO_BASED")
            .path("id").asString();
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String restaurants = systemCategoryId(member, RESTAURANTS_SLUG);

        // USD 1000 numerically equals the CAD 1000 income, but currencies are never blended.
        HttpResponse<String> mixed = postJson(appUrl("/v1/budgets/" + periodId + "/envelopes"),
            Json.object("categoryId", restaurants, "allocated", "1000.00", "currency", "USD"),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(mixed.statusCode())
            .as("a USD envelope in a CAD ZERO_BASED period is refused: %s", mixed.body())
            .isEqualTo(400);

        createEnvelope(member, periodId, groceries, "1000.00", "CAD");
    }

    @Test
    @DisplayName("PUT replaces the allocation; category, currency and spent are not writable")
    void updateReplacesTheAllocationOnly() {
        Session member = freshHousehold("envelope-update");
        String accountId = createManualAccount(member, "Chequing " + UUID.randomUUID());
        String periodId = createPeriodId(member, "2026-09-01");
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String envelopeId =
            createEnvelope(member, periodId, groceries, "400.00", "CAD").path("id").asString();

        JsonNode transaction = createManualTransaction(member, accountId, "60.00", "2026-09-04",
            "DEBIT", "GROCER " + UUID.randomUUID());
        patchJson(appUrl("/v1/transactions/" + transaction.path("id").asString()),
            Json.object("categoryId", groceries),
            "Authorization", "Bearer " + member.accessToken());

        JsonNode updated = Json.data(
            putJson(appUrl("/v1/budgets/" + periodId + "/envelopes/" + envelopeId),
                Json.object("allocated", "450.00"),
                "Authorization", "Bearer " + member.accessToken()).body());

        assertThat(new BigDecimal(updated.path("allocated").asString()))
            .isEqualByComparingTo(new BigDecimal("450.00"));
        assertThat(updated.path("categoryId").asString()).isEqualTo(groceries);
        assertThat(new BigDecimal(updated.path("spent").asString()))
            .as("nothing a member types into a budget form can change what they actually spent")
            .isEqualByComparingTo(new BigDecimal("60.00"));
    }

    @Test
    @DisplayName("health travels with every envelope and is computed, not stored")
    void healthIsComputedOnEveryRead() {
        Session member = freshHousehold("envelope-health");
        String periodId = createPeriodId(member, "2026-09-01");
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        JsonNode created = createEnvelope(member, periodId, groceries, "400.00", "CAD");

        JsonNode health = created.path("health");
        assertThat(new BigDecimal(health.path("remainingAmount").asString()))
            .isEqualByComparingTo(new BigDecimal("400.00"));
        assertThat(health.path("healthPercentage").asInt()).isZero();
        assertThat(health.path("paceMarkerPercentage").asInt()).isBetween(0, 100);

        assertThat(listEnvelopes(member, periodId).get(0).path("health").isObject())
            .as("the list carries it too: a list whose health the client would have to fetch one "
                + "row at a time is a list the client cannot render")
            .isTrue();
        assertThat(jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'envelope'",
            String.class))
            .as("and none of it is stored — every figure depends on the clock, not on the row")
            .doesNotContain("health_percentage", "pace_marker_percentage", "expected_spend");
    }

    @Test
    @DisplayName("an envelope paired with the wrong period is 404, never another period's row")
    void anEnvelopeMustBelongToThePeriodInThePath() {
        Session member = freshHousehold("envelope-pairing");
        String september = createPeriodId(member, "2026-09-01");
        String october = createPeriodId(member, "2026-10-01");
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String envelopeId =
            createEnvelope(member, september, groceries, "400.00", "CAD").path("id").asString();

        assertThat(get(appUrl("/v1/budgets/" + october + "/envelopes/" + envelopeId),
            member.accessToken()).statusCode())
            .as("the period is part of the lookup, not a check afterwards")
            .isEqualTo(404);
        assertThat(get(appUrl("/v1/budgets/" + september + "/envelopes/" + envelopeId),
            member.accessToken()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("deleting an envelope leaves the ledger and the category alone")
    void deletingAnEnvelopeRemovesNothingElse() {
        Session member = freshHousehold("envelope-delete");
        String accountId = createManualAccount(member, "Chequing " + UUID.randomUUID());
        String periodId = createPeriodId(member, "2026-09-01");
        String groceries = systemCategoryId(member, GROCERIES_SLUG);
        String envelopeId =
            createEnvelope(member, periodId, groceries, "400.00", "CAD").path("id").asString();
        JsonNode transaction = createManualTransaction(member, accountId, "25.00", "2026-09-06",
            "DEBIT", "GROCER " + UUID.randomUUID());

        assertThat(delete(appUrl("/v1/budgets/" + periodId + "/envelopes/" + envelopeId), null,
            "Authorization", "Bearer " + member.accessToken()).statusCode()).isEqualTo(200);

        assertThat(listEnvelopes(member, periodId)).isEmpty();
        assertThat(getTransaction(member, transaction.path("id").asString()).path("id").asString())
            .isEqualTo(transaction.path("id").asString());
        assertThat(systemCategoryId(member, GROCERIES_SLUG))
            .as("the category the envelope's foreign key was protecting is untouched")
            .isEqualTo(groceries);
    }

    @Test
    @DisplayName("household mates share the envelopes; another household sees 404")
    void envelopesAreSharedWithinTheHouseholdAndIsolatedBetweenThem() {
        Session admin = freshHousehold("envelope-shared");
        Session mate = inviteAndRegister(admin, "envelope-mate");
        Session outsider = freshHousehold("envelope-outsider");
        String periodId = createPeriodId(admin, "2026-09-01");
        String groceries = systemCategoryId(admin, GROCERIES_SLUG);
        String envelopeId =
            createEnvelope(admin, periodId, groceries, "400.00", "CAD").path("id").asString();

        List<JsonNode> asMate = listEnvelopes(mate, periodId);
        assertThat(asMate.stream().map(row -> row.path("id").asString()))
            .as("one shared ledger per household")
            .containsExactly(envelopeId);

        assertThat(get(appUrl("/v1/budgets/" + periodId + "/envelopes"), outsider.accessToken())
            .statusCode())
            .as("the period itself is invisible to another household, so its envelopes are too")
            .isEqualTo(404);
        assertThat(get(appUrl("/v1/budgets/" + periodId + "/envelopes/" + envelopeId),
            outsider.accessToken()).statusCode()).isEqualTo(404);
    }
}
