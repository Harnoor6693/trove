package finance.trove.users.admin;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.budgets.BudgetTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT /v1/admin/users/{id}/role} (journey bug F1-B2).
 *
 * <p>Before this route existed the last administrator was told to "give another member the
 * administrator role" and had no way to do it, so they could never delete their account or leave.
 * The invariant that matters is the opposite one: whatever order the calls arrive in, the household
 * never ends up with no active administrator.
 */
class MemberRoleIT extends BudgetTestSupport {

    private HttpResponse<String> changeRole(Session caller, UUID targetId, String role) {
        return putJson(appUrl("/v1/admin/users/" + targetId + "/role"), Json.object("role", role),
            "Authorization", "Bearer " + caller.accessToken());
    }

    private long activeAdminsIn(UUID householdId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE household_id = ? "
            + "AND role = 'ADMIN' AND is_active", Long.class, householdId);
    }

    private Session coAdmin(Session admin, String prefix) {
        String token = Json.data(postJson(appUrl("/v1/admin/invites"),
            Json.object("role", "ADMIN"), "Authorization", "Bearer " + admin.accessToken())
            .body()).path("token").asString();
        return registerWithInvite(token, uniqueEmail(prefix), MEMBER_PASSWORD);
    }

    @Test
    @DisplayName("Promoting a member lets the former last administrator delete their account")
    void promotionUnblocksTheLastAdministrator() {
        Session admin = freshHousehold("role-promote-admin");
        Session member = inviteAndRegister(admin, "role-promote-member");

        assertThat(delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + admin.accessToken()).statusCode())
            .as("the journey the bug describes: the only administrator cannot leave")
            .isEqualTo(409);

        HttpResponse<String> promoted = changeRole(admin, member.userId(), "ADMIN");
        assertThat(promoted.statusCode()).as("%s", promoted.body()).isEqualTo(200);
        assertThat(Json.data(promoted.body()).path("role").asString()).isEqualTo("ADMIN");
        assertThat(activeAdminsIn(admin.householdId())).isEqualTo(2);

        assertThat(delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + admin.accessToken()).statusCode())
            .as("somebody else can now administer the household")
            .isEqualTo(200);
        assertThat(activeAdminsIn(admin.householdId())).isEqualTo(1);
    }

    @Test
    @DisplayName("The household's last administrator cannot be demoted, not even by themselves")
    void theLastAdministratorCannotBeDemoted() {
        Session admin = freshHousehold("role-last-admin");

        HttpResponse<String> refused = changeRole(admin, admin.userId(), "MEMBER");

        assertThat(refused.statusCode()).as("%s", refused.body()).isEqualTo(409);
        assertThat(activeAdminsIn(admin.householdId())).isEqualTo(1);
    }

    @Test
    @DisplayName("A demoted administrator loses the role at once, not when their token expires")
    void demotionTakesEffectImmediately() {
        Session admin = freshHousehold("role-demote-admin");
        Session other = coAdmin(admin, "role-demote-other");
        assertThat(get(appUrl("/v1/admin/users"), other.accessToken()).statusCode())
            .isEqualTo(200);

        HttpResponse<String> demoted = changeRole(admin, other.userId(), "MEMBER");

        assertThat(demoted.statusCode()).as("%s", demoted.body()).isEqualTo(200);
        assertThat(Json.data(demoted.body()).path("role").asString()).isEqualTo("MEMBER");
        assertThat(get(appUrl("/v1/admin/users"), other.accessToken()).statusCode())
            .as("the access token still says ADMIN; its session has to die with the role")
            .isEqualTo(401);
        Session again = loginBearer(other.email(), MEMBER_PASSWORD);
        assertThat(get(appUrl("/v1/admin/users"), again.accessToken()).statusCode())
            .isEqualTo(403);
    }

    @Test
    @DisplayName("A member cannot change roles, and another household's member is 404")
    void onlyThisHouseholdsAdministratorMayChangeRoles() {
        Session admin = freshHousehold("role-scope-admin");
        Session member = inviteAndRegister(admin, "role-scope-member");
        Session stranger = freshHousehold("role-scope-stranger");

        assertThat(changeRole(member, member.userId(), "ADMIN").statusCode()).isEqualTo(403);
        assertThat(changeRole(stranger, member.userId(), "MEMBER").statusCode())
            .as("not yours and does not exist are the same answer")
            .isEqualTo(404);
        assertThat(activeAdminsIn(admin.householdId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Two administrators demoting each other at once leave exactly one behind")
    void theGuardHoldsUnderConcurrency() throws Exception {
        Session first = freshHousehold("role-race-first");
        Session second = coAdmin(first, "role-race-second");
        assertThat(activeAdminsIn(first.householdId())).isEqualTo(2);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = List.of(
                pool.submit(() -> race(ready, go, first, second.userId())),
                pool.submit(() -> race(ready, go, second, first.userId())));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            List<Integer> statuses = List.of(results.get(0).get(60, TimeUnit.SECONDS),
                results.get(1).get(60, TimeUnit.SECONDS));
            assertThat(statuses)
                .as("the loser is refused: either 409 by the guard, or 401 because the winner "
                    + "demoted it and its session went with the role")
                .contains(200)
                .doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
        assertThat(activeAdminsIn(first.householdId())).isEqualTo(1);
    }

    private Integer race(CountDownLatch ready, CountDownLatch go, Session caller, UUID target)
        throws InterruptedException {
        ready.countDown();
        go.await(30, TimeUnit.SECONDS);
        return changeRole(caller, target, "MEMBER").statusCode();
    }
}
