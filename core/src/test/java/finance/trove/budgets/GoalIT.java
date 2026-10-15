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

/** {@code /v1/goals} end to end ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6). */
class GoalIT extends BudgetTestSupport {

    @Test
    @DisplayName("a goal starts at zero and unmet, whatever the caller hoped")
    void aGoalStartsAtZeroAndUnmet() {
        Session member = freshHousehold("goal-create");

        JsonNode created = createGoal(member, "Emergency Fund", "10000.00", "CAD", "2026-12-31");

        assertThat(new BigDecimal(created.path("currentAmount").asString()))
            .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(created.path("achieved").asBoolean())
            .as("a goal cannot be created already met")
            .isFalse();
        assertThat(created.path("deadline").asString()).isEqualTo("2026-12-31");
        assertThat(created.path("createdBy").asString()).isEqualTo(member.userId().toString());
    }

    @Test
    @DisplayName("an omitted currency comes from the creating member and an omitted deadline is "
        + "open-ended")
    void currencyAndDeadlineDefaults() {
        Session member = freshHousehold("goal-defaults");

        HttpResponse<String> response = postJson(appUrl("/v1/goals"),
            Json.object("name", "Open Ended", "targetAmount", "500.00", "currency", null,
                "deadline", null),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(Json.data(response.body()).path("currency").asString())
            .as("never a CAD literal — finding F1")
            .isEqualTo("CAD");
        assertThat(Json.data(response.body()).path("deadline").isNull()).isTrue();
    }

    @Test
    @DisplayName("achieved is derived on every write, in both directions")
    void achievedIsDerivedAndNeverSent() {
        Session member = freshHousehold("goal-achieved");
        String goalId = createGoal(member, "Vacation", "1000.00", "CAD", null)
            .path("id").asString();

        JsonNode met = Json.data(putJson(appUrl("/v1/goals/" + goalId),
            Json.object("name", "Vacation", "targetAmount", "1000.00", "currentAmount",
                "1000.00", "deadline", null),
            "Authorization", "Bearer " + member.accessToken()).body());
        assertThat(met.path("achieved").asBoolean())
            .as("the contribution that completed it marks it met without a second call")
            .isTrue();

        JsonNode raised = Json.data(putJson(appUrl("/v1/goals/" + goalId),
            Json.object("name", "Vacation", "targetAmount", "2000.00", "currentAmount",
                "1000.00", "deadline", null),
            "Authorization", "Bearer " + member.accessToken()).body());
        assertThat(raised.path("achieved").asBoolean())
            .as("raising the target un-meets it: the flag is a fact about the two amounts, not a "
                + "sticky state the source's public setter could leave disagreeing with them")
            .isFalse();
    }

    @Test
    @DisplayName("PUT is a full replace, so an omitted deadline clears it")
    void anOmittedDeadlineClearsIt() {
        Session member = freshHousehold("goal-deadline");
        String goalId = createGoal(member, "Bike", "800.00", "CAD", "2026-11-30")
            .path("id").asString();

        JsonNode cleared = Json.data(putJson(appUrl("/v1/goals/" + goalId),
            Json.object("name", "Bike", "targetAmount", "800.00", "currentAmount", "100.00",
                "deadline", null),
            "Authorization", "Bearer " + member.accessToken()).body());

        assertThat(cleared.path("deadline").isNull())
            .as("the source's partial patch could only ever set a deadline, never remove one")
            .isTrue();
    }

    @Test
    @DisplayName("goals are shared inside the household and invisible outside it")
    void goalsAreSharedAndIsolated() {
        Session admin = freshHousehold("goal-shared");
        Session mate = inviteAndRegister(admin, "goal-mate");
        Session outsider = freshHousehold("goal-outsider");
        String goalId = createGoal(admin, "House Deposit " + UUID.randomUUID(), "50000.00", "CAD",
            null).path("id").asString();

        List<JsonNode> asMate =
            Json.elements(Json.data(get(appUrl("/v1/goals"), mate.accessToken()).body()));
        assertThat(asMate.stream().map(row -> row.path("id").asString()))
            .as("a savings goal is household money under the shared-ledger decision; the "
                + "pre-collapse per-user scoping hid one partner's emergency fund from the other")
            .contains(goalId);

        List<JsonNode> asOutsider =
            Json.elements(Json.data(get(appUrl("/v1/goals"), outsider.accessToken()).body()));
        assertThat(asOutsider.stream().map(row -> row.path("id").asString()))
            .doesNotContain(goalId);
        assertThat(delete(appUrl("/v1/goals/" + goalId), null,
            "Authorization", "Bearer " + outsider.accessToken()).statusCode())
            .as("404 and not 403: a 403 would confirm the goal exists")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("a goal is deleted, and an unknown id is 404")
    void deletingAGoal() {
        Session member = freshHousehold("goal-delete");
        String goalId = createGoal(member, "Camera", "1200.00", "CAD", null)
            .path("id").asString();

        assertThat(delete(appUrl("/v1/goals/" + goalId), null,
            "Authorization", "Bearer " + member.accessToken()).statusCode()).isEqualTo(200);
        assertThat(delete(appUrl("/v1/goals/" + goalId), null,
            "Authorization", "Bearer " + member.accessToken()).statusCode()).isEqualTo(404);
        assertThat(delete(appUrl("/v1/goals/" + unknownId()), null,
            "Authorization", "Bearer " + member.accessToken()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a blank name is refused with a field error")
    void aBlankNameIsRefused() {
        Session member = freshHousehold("goal-validation");

        // No opt-out: three spaces satisfy minLength: 1, so the request genuinely conforms and
        // it is the SERVER's @NotBlank that refuses it — which is exactly what the schema's own
        // description says it will do.
        HttpResponse<String> response = postJson(appUrl("/v1/goals"),
            Json.object("name", "   ", "targetAmount", "100.00", "currency", "CAD",
                "deadline", null),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("B2-13: a target amount beyond the money format (5 decimals) is refused")
    void anOverPreciseTargetIsRefused() {
        Session member = freshHousehold("goal-precision");

        HttpResponse<String> response = withoutRequestValidation(
            "sending a MoneyAmount the schema pattern forbids, to prove the server refuses it too",
            () -> postJson(appUrl("/v1/goals"),
                Json.object("name", "Holiday", "targetAmount", "19.99999", "currency", "CAD",
                    "deadline", null),
                "Authorization", "Bearer " + member.accessToken()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }
}
