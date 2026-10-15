package finance.trove.categorisation.rules;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.categorisation.MappingSource;
import finance.trove.categorisation.MatchType;
import finance.trove.categorisation.MerchantMappingWriter;
import finance.trove.categorisation.MerchantNormaliser;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * {@code /v1/categories/rules} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.5) — CRUD, reorder
 * and the dry-run — plus the end-to-end proof of the D4 prefix-query-direction fix
 * ({@code docs/recovery/01-FINDINGS.md}, {@code docs/recovery/inventory/
 * categorisation-service.md} §10): a mocked-repository unit test cannot catch a query built
 * backwards, which is exactly how the old service's own test suite missed it, so
 * {@link #prefixRuleMatchesALongerMerchantString()} runs the real derived SQL against a real
 * Postgres row.
 */
class CategorisationRuleIT extends AuthFlowSupport {

    @Autowired
    private MerchantMappingWriter mappingWriter;

    private String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    /**
     * A random uppercase token, used as (part of) a merchant string in every test below.
     *
     * <p>Every {@code inviteAndRegister(loginBootstrapAdmin(), …)} call in this suite invites a
     * new member into the SAME shared, never-truncated household ({@code AuthFlowSupport}'s
     * bootstrap admin — see {@code BaseIT}'s "household A is the suite-wide bootstrap admin"),
     * and {@code merchant_category_map}'s unique constraint is
     * {@code (household_id, normalised_merchant)} — scoped to that ONE shared household, not to
     * this test method. A fixed literal like {@code "Starbucks"} reused across two test methods
     * therefore collides for real, exactly as it would across two genuine callers, and the second
     * one gets a real 409. Every merchant string in this file is built from this token so that
     * different test methods can never collide with each other or with a previous run's leftover
     * rows on the shared suite database.
     */
    private static String uniqueBrand() {
        return "BR" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private String coffeeShopsId(Session session) {
        return Json.elements(Json.data(
                get(appUrl("/v1/categories"), session.accessToken()).body())).stream()
            .filter(c -> "food.coffee".equals(c.path("slug").asString()))
            .findFirst()
            .orElseThrow()
            .path("id").asString();
    }

    @Test
    @DisplayName("Create, list, update and delete a rule")
    void ruleCrud() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-crud");
        String coffee = coffeeShopsId(member);
        String brand = uniqueBrand();

        HttpResponse<String> created = postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", brand, "matchType", "EXACT", "categoryId", coffee,
                "confidence", "0.950"),
            "Authorization", bearer(member));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode createdData = Json.data(created.body());
        assertThat(createdData.path("merchant").asString())
            .as("stored as the normalised form")
            .isEqualTo(brand);
        assertThat(createdData.path("categoryName").asString()).isEqualTo("Coffee Shops");
        assertThat(createdData.path("source").asString()).isEqualTo("RULE");
        String ruleId = createdData.path("id").asString();

        List<JsonNode> listed =
            Json.elements(Json.data(get(appUrl("/v1/categories/rules"), member.accessToken())
                .body()));
        assertThat(listed.stream().map(r -> r.path("id").asString())).contains(ruleId);

        HttpResponse<String> updated = putJson(appUrl("/v1/categories/rules/" + ruleId),
            Json.object("merchant", brand + " COFFEE", "matchType", "PREFIX", "categoryId",
                coffee),
            "Authorization", bearer(member));
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(Json.data(updated.body()).path("matchType").asString()).isEqualTo("PREFIX");

        assertThat(delete(appUrl("/v1/categories/rules/" + ruleId), null,
            "Authorization", bearer(member)).statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(
                get(appUrl("/v1/categories/rules"), member.accessToken()).body())).stream()
            .map(r -> r.path("id").asString()))
            .doesNotContain(ruleId);
    }

    @Test
    @DisplayName("A second rule for the same merchant is 409 conflict")
    void duplicateMerchantIsConflict() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-dupe");
        String coffee = coffeeShopsId(member);
        String brand = uniqueBrand();

        assertThat(postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", brand, "matchType", "EXACT", "categoryId", coffee),
            "Authorization", bearer(member)).statusCode()).isEqualTo(201);

        HttpResponse<String> second = postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", brand.toLowerCase(java.util.Locale.ROOT), "matchType", "EXACT",
                "categoryId", coffee),
            "Authorization", bearer(member));
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(second.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("Updating a rule without changing its merchant only touches the other fields")
    void updateWithoutChangingMerchantIsFine() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-update-samemerchant");
        String coffee = coffeeShopsId(member);
        String fastFood = Json.elements(Json.data(
                get(appUrl("/v1/categories"), member.accessToken()).body())).stream()
            .filter(c -> "food.fastfood".equals(c.path("slug").asString()))
            .findFirst().orElseThrow().path("id").asString();
        String brand = uniqueBrand();

        String ruleId = Json.data(postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", brand, "matchType", "EXACT", "categoryId", coffee),
            "Authorization", bearer(member)).body()).path("id").asString();

        HttpResponse<String> updated = putJson(appUrl("/v1/categories/rules/" + ruleId),
            Json.object("merchant", brand, "matchType", "EXACT", "categoryId", fastFood,
                "confidence", "0.750"),
            "Authorization", bearer(member));

        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        JsonNode data = Json.data(updated.body());
        assertThat(data.path("merchant").asString()).isEqualTo(brand);
        assertThat(data.path("categoryId").asString()).isEqualTo(fastFood);
        assertThat(data.path("confidence").asString()).isEqualTo("0.750");
    }

    @Test
    @DisplayName("Renaming a rule's merchant onto another rule's merchant is 409 conflict")
    void renamingOntoAnotherRulesMerchantIsConflict() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-rename-collide");
        String coffee = coffeeShopsId(member);
        String brandOne = uniqueBrand();
        String brandTwo = uniqueBrand();

        postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", brandOne, "matchType", "EXACT", "categoryId", coffee),
            "Authorization", bearer(member));
        String ruleTwoId = Json.data(postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", brandTwo, "matchType", "EXACT", "categoryId", coffee),
            "Authorization", bearer(member)).body()).path("id").asString();

        HttpResponse<String> renamed = putJson(appUrl("/v1/categories/rules/" + ruleTwoId),
            Json.object("merchant", brandOne, "matchType", "EXACT", "categoryId", coffee),
            "Authorization", bearer(member));

        assertThat(renamed.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(renamed.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("An unresolvable categoryId is 400 validation-error")
    void unknownCategoryIsRejected() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-badcat");

        HttpResponse<String> response = postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", "Somewhere", "matchType", "EXACT", "categoryId",
                UUID.randomUUID().toString()),
            "Authorization", bearer(member));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("An unowned or unknown rule id is 404 on update and delete")
    void unknownRuleIs404() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-404");
        String coffee = coffeeShopsId(member);
        String randomId = UUID.randomUUID().toString();

        assertThat(putJson(appUrl("/v1/categories/rules/" + randomId),
            Json.object("merchant", "X", "matchType", "EXACT", "categoryId", coffee),
            "Authorization", bearer(member)).statusCode()).isEqualTo(404);
        assertThat(delete(appUrl("/v1/categories/rules/" + randomId), null,
            "Authorization", bearer(member)).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Reorder sets priority to each id's position; an unowned id refuses the whole call")
    void reorderSetsPriority() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-reorder");
        String coffee = coffeeShopsId(member);

        String first = Json.data(postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", uniqueBrand(), "matchType", "PREFIX", "categoryId", coffee),
            "Authorization", bearer(member)).body()).path("id").asString();
        String second = Json.data(postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", uniqueBrand(), "matchType", "PREFIX", "categoryId", coffee),
            "Authorization", bearer(member)).body()).path("id").asString();

        HttpResponse<String> reordered = postJson(appUrl("/v1/categories/rules/reorder"),
            Json.object("orderedIds", List.of(second, first)),
            "Authorization", bearer(member));
        assertThat(reordered.statusCode()).as(reordered.body()).isEqualTo(200);

        List<JsonNode> listed =
            Json.elements(Json.data(get(appUrl("/v1/categories/rules"), member.accessToken())
                .body()));
        int secondPriority = listed.stream()
            .filter(r -> second.equals(r.path("id").asString())).findFirst().orElseThrow()
            .path("priority").asInt();
        int firstPriority = listed.stream()
            .filter(r -> first.equals(r.path("id").asString())).findFirst().orElseThrow()
            .path("priority").asInt();
        assertThat(secondPriority).isLessThan(firstPriority);

        HttpResponse<String> refused = postJson(appUrl("/v1/categories/rules/reorder"),
            Json.object("orderedIds", List.of(UUID.randomUUID().toString())),
            "Authorization", bearer(member));
        assertThat(refused.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("A resolvable but non-RULE-sourced mapping is 403 forbidden on update, delete "
        + "and reorder — not editable through this API")
    void nonRuleSourcedMappingIsForbidden() {
        // ownRule() (the shared guard behind update/delete/reorder) resolves the row for this
        // household fine, then refuses it because MerchantMappingWriter's automated writes
        // (source USER/AI — the seam step 16's learning listener uses later; MappingIT proves it
        // directly since nothing calls it over HTTP yet) are not rules created through this API.
        // That branch was previously untested and undocumented in the spec (openapi review
        // finding: reorder's responses omitted 403 entirely) — this is the regression test for
        // both.
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-nonrule-source");
        UUID coffee = UUID.fromString(coffeeShopsId(member));
        String merchant = "NONRULE " + uniqueBrand();

        mappingWriter.upsert(member.householdId(), member.userId(), merchant, MatchType.EXACT,
            coffee, new BigDecimal("0.700"), MappingSource.USER);
        UUID learnedId = jdbcTemplate.queryForObject(
            "select id from merchant_category_map where household_id = ? "
                + "and normalised_merchant = ?",
            UUID.class, member.householdId(), MerchantNormaliser.normalise(merchant));

        HttpResponse<String> updateAttempt = putJson(
            appUrl("/v1/categories/rules/" + learnedId),
            Json.object("merchant", merchant, "matchType", "EXACT", "categoryId",
                coffee.toString()),
            "Authorization", bearer(member));
        assertThat(updateAttempt.statusCode()).isEqualTo(403);
        assertThat(Json.errorCode(updateAttempt.body())).isEqualTo("forbidden");

        HttpResponse<String> deleteAttempt = delete(
            appUrl("/v1/categories/rules/" + learnedId), null, "Authorization", bearer(member));
        assertThat(deleteAttempt.statusCode()).isEqualTo(403);
        assertThat(Json.errorCode(deleteAttempt.body())).isEqualTo("forbidden");

        HttpResponse<String> reorderAttempt = postJson(appUrl("/v1/categories/rules/reorder"),
            Json.object("orderedIds", List.of(learnedId.toString())),
            "Authorization", bearer(member));
        assertThat(reorderAttempt.statusCode()).isEqualTo(403);
        assertThat(Json.errorCode(reorderAttempt.body())).isEqualTo("forbidden");
    }

    @Test
    @DisplayName("Dry-run: an EXACT rule wins over a PREFIX rule for the identical normalised string")
    void dryRunPrefersExactOverPrefix() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-dryrun-exact");
        String coffee = coffeeShopsId(member);
        String fastFood = Json.elements(Json.data(
                get(appUrl("/v1/categories"), member.accessToken()).body())).stream()
            .filter(c -> "food.fastfood".equals(c.path("slug").asString()))
            .findFirst().orElseThrow().path("id").asString();
        String brand = uniqueBrand();
        String brandPrefix = brand.substring(0, brand.length() - 3);

        postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", brandPrefix, "matchType", "PREFIX", "categoryId", fastFood),
            "Authorization", bearer(member));
        postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", brand, "matchType", "EXACT", "categoryId", coffee),
            "Authorization", bearer(member));

        HttpResponse<String> dryRun = postJson(appUrl("/v1/categories/rules/dry-run"),
            Json.object("merchant", brand), "Authorization", bearer(member));

        assertThat(dryRun.statusCode()).as(dryRun.body()).isEqualTo(200);
        JsonNode result = Json.data(dryRun.body());
        assertThat(result.path("categoryId").asString()).isEqualTo(coffee);
        assertThat(result.path("source").asString()).isEqualTo("RULE");
    }

    @Test
    @DisplayName("A stored short PREFIX rule matches a longer merchant string — the D4 direction fix")
    void prefixRuleMatchesALongerMerchantString() {
        // The old service's derived query required the STORED value to start with the SUPPLIED
        // argument, backwards from its own javadoc's stated intent, so a short stored prefix like
        // "STAR" never matched a lookup for the longer "STARBUCKS DOWNTOWN". This test creates
        // exactly that shape — a short PREFIX rule and a longer sample string — and proves the
        // fixed query resolves it, against a real Postgres row, not a mock.
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-dryrun-prefix");
        String coffee = coffeeShopsId(member);
        String brand = uniqueBrand();
        String shortPrefix = brand.substring(0, 5);

        HttpResponse<String> rule = postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", shortPrefix, "matchType", "PREFIX", "categoryId", coffee),
            "Authorization", bearer(member));
        assertThat(rule.statusCode()).as(rule.body()).isEqualTo(201);

        HttpResponse<String> dryRun = postJson(appUrl("/v1/categories/rules/dry-run"),
            Json.object("merchant", brand + " COFFEE DOWNTOWN"), "Authorization", bearer(member));

        assertThat(dryRun.statusCode()).as(dryRun.body()).isEqualTo(200);
        JsonNode result = Json.data(dryRun.body());
        assertThat(result.path("categoryId").asString())
            .as("the short stored prefix is a real prefix of the longer sample merchant once "
                + "normalised")
            .isEqualTo(coffee);
        assertThat(result.path("source").asString()).isEqualTo("RULE");
    }

    @Test
    @DisplayName("The longer, more specific stored prefix wins when two prefixes both match")
    void longestPrefixWinsAmongMultipleMatches() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-dryrun-longest");
        String coffee = coffeeShopsId(member);
        String fastFood = Json.elements(Json.data(
                get(appUrl("/v1/categories"), member.accessToken()).body())).stream()
            .filter(c -> "food.fastfood".equals(c.path("slug").asString()))
            .findFirst().orElseThrow().path("id").asString();
        String brand = uniqueBrand();
        String shortPrefix = brand.substring(0, 5);
        String longPrefix = brand + " COFFEE";

        postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", shortPrefix, "matchType", "PREFIX", "categoryId", fastFood),
            "Authorization", bearer(member));
        postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", longPrefix, "matchType", "PREFIX", "categoryId", coffee),
            "Authorization", bearer(member));

        HttpResponse<String> dryRun = postJson(appUrl("/v1/categories/rules/dry-run"),
            Json.object("merchant", brand + " COFFEE DOWNTOWN"), "Authorization", bearer(member));

        assertThat(Json.data(dryRun.body()).path("categoryId").asString())
            .as("the longer, more specific stored prefix must win")
            .isEqualTo(coffee);
    }

    @Test
    @DisplayName("Dry-run falls through to the community registry, then to Uncategorised")
    void dryRunFallsThroughTheTiers() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "rule-dryrun-fallback");

        HttpResponse<String> community = postJson(appUrl("/v1/categories/rules/dry-run"),
            Json.object("merchant", "Netflix"), "Authorization", bearer(member));
        assertThat(community.statusCode()).as(community.body()).isEqualTo(200);
        JsonNode communityResult = Json.data(community.body());
        assertThat(communityResult.path("categoryName").asString()).isEqualTo("Streaming");
        assertThat(communityResult.path("source").isNull())
            .as("a community-registry match is not a persisted household row")
            .isTrue();

        HttpResponse<String> fallback = postJson(appUrl("/v1/categories/rules/dry-run"),
            Json.object("merchant", "A Merchant Nobody Has Ever Heard Of " + UUID.randomUUID()),
            "Authorization", bearer(member));
        assertThat(fallback.statusCode()).as(fallback.body()).isEqualTo(200);
        JsonNode fallbackResult = Json.data(fallback.body());
        assertThat(fallbackResult.path("categoryName").asString()).isEqualTo("Uncategorised");
        assertThat(fallbackResult.path("confidence").asString()).isEqualTo("0.000");
    }

    @Test
    @DisplayName("Every rule route requires a session")
    void ruleRoutesRequireASession() {
        assertThat(get(appUrl("/v1/categories/rules")).statusCode()).isEqualTo(401);
        assertThat(postJson(appUrl("/v1/categories/rules/dry-run"),
            Json.object("merchant", "X")).statusCode()).isEqualTo(401);
    }
}
