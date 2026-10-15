package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code /v1/categories} CRUD ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.5): the system seed
 * is visible and read-only, household categories are fully editable, and a category still in use
 * by one of the household's own merchant rules cannot be deleted.
 */
class CategoryIT extends AuthFlowSupport {

    private String bearer(Session session) {
        return "Bearer " + session.accessToken();
    }

    @Test
    @DisplayName("The system seed is present, read-only, and visible to a freshly registered member")
    void systemSeedIsPresentAndReadOnly() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "cat-seed");

        HttpResponse<String> response = get(appUrl("/v1/categories"), member.accessToken());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        List<JsonNode> categories = Json.elements(Json.data(response.body()));
        // Not an absolute row count (a household may already have added rows on a shared test
        // database): a floor, plus specific known slugs, is what proves the seed ran.
        assertThat(categories.size()).isGreaterThanOrEqualTo(51);

        JsonNode coffee = categories.stream()
            .filter(c -> "food.coffee".equals(c.path("slug").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("food.coffee is missing from the seed"));
        assertThat(coffee.path("name").asString()).isEqualTo("Coffee Shops");
        assertThat(coffee.path("householdId").isNull())
            .as("a system category's householdId is null")
            .isTrue();
        assertThat(coffee.path("isSystem").asBoolean()).isTrue();

        assertThat(categories.stream().map(c -> c.path("slug").asString()))
            .as("the mandatory pipeline fallback must exist under its stable slug")
            .contains("system.uncategorised");
    }

    @Test
    @DisplayName("A household member creates, renames and deletes their own category")
    void createRenameDeleteHouseholdCategory() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "cat-crud");

        HttpResponse<String> created = postJson(appUrl("/v1/categories"),
            Json.object("name", "Board Game Nights", "icon", "dice"),
            "Authorization", bearer(member));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode createdData = Json.data(created.body());
        assertThat(createdData.path("name").asString()).isEqualTo("Board Game Nights");
        assertThat(createdData.path("isSystem").asBoolean()).isFalse();
        assertThat(createdData.path("householdId").asString())
            .isEqualTo(member.householdId().toString());
        assertThat(createdData.path("slug").asString()).isNotBlank();
        String id = createdData.path("id").asString();

        HttpResponse<String> renamed = putJson(appUrl("/v1/categories/" + id),
            Json.object("name", "Game Night", "icon", "dice"), "Authorization", bearer(member));
        assertThat(renamed.statusCode()).as(renamed.body()).isEqualTo(200);
        assertThat(Json.data(renamed.body()).path("name").asString()).isEqualTo("Game Night");

        HttpResponse<String> deleted = delete(appUrl("/v1/categories/" + id), null,
            "Authorization", bearer(member));
        assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(200);

        List<JsonNode> afterDelete =
            Json.elements(Json.data(get(appUrl("/v1/categories"), member.accessToken()).body()));
        assertThat(afterDelete.stream().map(c -> c.path("id").asString()))
            .doesNotContain(id);
    }

    @Test
    @DisplayName("Two categories with the same name get different slugs in one household")
    void slugsAreDeduplicatedWithinAHousehold() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "cat-slug");

        String firstSlug = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "Side Projects"), "Authorization", bearer(member)).body())
            .path("slug").asString();
        String secondSlug = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "Side Projects"), "Authorization", bearer(member)).body())
            .path("slug").asString();

        assertThat(secondSlug).isNotEqualTo(firstSlug);
    }

    @Test
    @DisplayName("A system category cannot be renamed or deleted")
    void systemCategoryIsImmutable() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "cat-immutable");
        String systemId = systemCategoryId(member, "food.coffee");

        HttpResponse<String> renameAttempt = putJson(appUrl("/v1/categories/" + systemId),
            Json.object("name", "Hijacked"), "Authorization", bearer(member));
        assertThat(renameAttempt.statusCode()).isEqualTo(403);
        assertThat(Json.errorCode(renameAttempt.body())).isEqualTo("forbidden");

        HttpResponse<String> deleteAttempt = delete(appUrl("/v1/categories/" + systemId), null,
            "Authorization", bearer(member));
        assertThat(deleteAttempt.statusCode()).isEqualTo(403);
        assertThat(Json.errorCode(deleteAttempt.body())).isEqualTo("forbidden");

        JsonNode stillThere = Json.elements(Json.data(
                get(appUrl("/v1/categories"), member.accessToken()).body())).stream()
            .filter(c -> systemId.equals(c.path("id").asString()))
            .findFirst()
            .orElseThrow();
        assertThat(stillThere.path("name").asString())
            .as("the refused rename must not have taken effect")
            .isEqualTo("Coffee Shops");
    }

    @Test
    @DisplayName("parentId round-trips, and re-parenting a category under its own descendant is rejected")
    void parentIdRoundTripsAndCyclesAreRejected() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "cat-parent");

        String parentId = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "Parent Category"), "Authorization", bearer(member)).body())
            .path("id").asString();
        HttpResponse<String> childCreated = postJson(appUrl("/v1/categories"),
            Json.object("name", "Child Category", "parentId", parentId),
            "Authorization", bearer(member));
        assertThat(childCreated.statusCode()).as(childCreated.body()).isEqualTo(201);
        String childId = Json.data(childCreated.body()).path("id").asString();
        assertThat(Json.data(childCreated.body()).path("parentId").asString()).isEqualTo(parentId);

        // Re-parenting the CHILD under itself is the direct cycle.
        HttpResponse<String> selfParent = putJson(appUrl("/v1/categories/" + childId),
            Json.object("name", "Child Category", "parentId", childId),
            "Authorization", bearer(member));
        assertThat(selfParent.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(selfParent.body())).isEqualTo("validation-error");

        // Re-parenting the PARENT under its own child is the one-hop cycle.
        HttpResponse<String> indirectCycle = putJson(appUrl("/v1/categories/" + parentId),
            Json.object("name", "Parent Category", "parentId", childId),
            "Authorization", bearer(member));
        assertThat(indirectCycle.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(indirectCycle.body())).isEqualTo("validation-error");

        // The refused writes must not have taken effect.
        JsonNode parentAfter = Json.elements(Json.data(
                get(appUrl("/v1/categories"), member.accessToken()).body())).stream()
            .filter(c -> parentId.equals(c.path("id").asString())).findFirst().orElseThrow();
        assertThat(parentAfter.path("parentId").isNull()).isTrue();

        // Clearing the child's parent (parentId: null) is an ordinary, accepted edit.
        HttpResponse<String> cleared = putJson(appUrl("/v1/categories/" + childId),
            Json.object("name", "Child Category", "parentId", null),
            "Authorization", bearer(member));
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(Json.data(cleared.body()).path("parentId").isNull()).isTrue();
    }

    @Test
    @DisplayName("An unresolvable parentId is 400 validation-error")
    void unresolvableParentIdIsRejected() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "cat-badparent");

        HttpResponse<String> response = postJson(appUrl("/v1/categories"),
            Json.object("name", "Orphan", "parentId", java.util.UUID.randomUUID().toString()),
            "Authorization", bearer(member));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("A category referenced by a merchant rule cannot be deleted; 409, then succeeds")
    void deletingACategoryInUseIs409() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "cat-inuse");
        String categoryId = Json.data(postJson(appUrl("/v1/categories"),
            Json.object("name", "Very Specific Hobby"), "Authorization", bearer(member)).body())
            .path("id").asString();

        HttpResponse<String> rule = postJson(appUrl("/v1/categories/rules"),
            Json.object("merchant", "HOBBY SHOP", "matchType", "EXACT", "categoryId", categoryId),
            "Authorization", bearer(member));
        assertThat(rule.statusCode()).as(rule.body()).isEqualTo(201);
        String ruleId = Json.data(rule.body()).path("id").asString();

        HttpResponse<String> blockedDelete = delete(appUrl("/v1/categories/" + categoryId), null,
            "Authorization", bearer(member));
        assertThat(blockedDelete.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(blockedDelete.body())).isEqualTo("conflict");

        assertThat(delete(appUrl("/v1/categories/rules/" + ruleId), null,
            "Authorization", bearer(member)).statusCode()).isEqualTo(200);

        HttpResponse<String> nowDeletable = delete(appUrl("/v1/categories/" + categoryId), null,
            "Authorization", bearer(member));
        assertThat(nowDeletable.statusCode()).as(nowDeletable.body()).isEqualTo(200);
    }

    @Test
    @DisplayName("An unknown id is 404 on both PUT and DELETE")
    void unknownIdIs404() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "cat-404");
        String randomId = java.util.UUID.randomUUID().toString();

        assertThat(putJson(appUrl("/v1/categories/" + randomId), Json.object("name", "X"),
            "Authorization", bearer(member)).statusCode()).isEqualTo(404);
        assertThat(delete(appUrl("/v1/categories/" + randomId), null,
            "Authorization", bearer(member)).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("A blank name is 400 validation-error")
    void blankNameIsRejected() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "cat-blank");

        HttpResponse<String> response = postJson(appUrl("/v1/categories"),
            Json.object("name", "   "), "Authorization", bearer(member));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("Every category route requires a session")
    void categoriesRequireASession() {
        assertThat(get(appUrl("/v1/categories")).statusCode()).isEqualTo(401);
        assertThat(postJson(appUrl("/v1/categories"), Json.object("name", "X")).statusCode())
            .isEqualTo(401);
    }

    private String systemCategoryId(Session member, String slug) {
        return Json.elements(Json.data(
                get(appUrl("/v1/categories"), member.accessToken()).body())).stream()
            .filter(c -> slug.equals(c.path("slug").asString()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(slug + " is missing from the seed"))
            .path("id").asString();
    }
}
