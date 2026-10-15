package finance.trove.users.admin;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.budgets.BudgetTestSupport;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code POST /v1/admin/users/{id}/restore}: the other half of the soft delete, and the reason the
 * delete is soft at all (collapse step 18; gap row 16's "7-day soft-delete grace with admin
 * restore").
 *
 * <p>The failure this exists to prevent is mundane: a member deletes in anger, or an administrator
 * removes the wrong person, and a working week later there is nothing anybody can do about it.
 */
class AccountRestoreIT extends BudgetTestSupport {

    private String restoreUrl(UUID userId) {
        return appUrl("/v1/admin/users/" + userId + "/restore");
    }

    /**
     * The restore call. No body: the operation declares none, exactly as
     * {@code POST /v1/admin/users/{id}/unlock} does, and the conformance validator refuses one.
     */
    private HttpResponse<String> restore(Session admin, UUID userId) {
        return postJson(restoreUrl(userId), null,
            "Authorization", "Bearer " + admin.accessToken());
    }

    private void backdateDeletion(UUID userId, String interval) {
        jdbcTemplate.update("UPDATE users SET deleted_at = now() - interval '" + interval + "' "
            + "WHERE id = ?", userId);
    }

    @Test
    @DisplayName("An admin restores a member inside the grace and they can sign in again")
    void aRestoredMemberCanSignInAgain() {
        Session admin = freshHousehold("restore-admin");
        Session member = inviteAndRegister(admin, "restore-member");
        assertThat(delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken()).statusCode()).isEqualTo(200);
        assertThat(postJson(appUrl("/v1/auth/login"),
            Json.object("email", member.email(), "password", MEMBER_PASSWORD),
            "X-Trove-Auth-Mode", "bearer").statusCode())
            .as("gone during the grace, or the grace is not a deletion at all")
            .isEqualTo(401);

        HttpResponse<String> restored = restore(admin, member.userId());

        assertThat(restored.statusCode()).as("%s", restored.body()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForMap(
            "SELECT is_active, deleted_at, deleted_by FROM users WHERE id = ?", member.userId()))
            .containsEntry("is_active", true)
            .containsEntry("deleted_at", null)
            .containsEntry("deleted_by", null);
        assertThat(postJson(appUrl("/v1/auth/login"),
            Json.object("email", member.email(), "password", MEMBER_PASSWORD),
            "X-Trove-Auth-Mode", "bearer").statusCode())
            .as("the member signs in again with the credentials they already had — the sessions "
                + "are NOT brought back, because a revoked family cannot be un-revoked")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("A restore records the event against the acting administrator")
    void aRestoreIsRecorded() {
        Session admin = freshHousehold("restore-audit-admin");
        Session member = inviteAndRegister(admin, "restore-audit-member");
        delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(restore(admin, member.userId()).statusCode()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_DELETE' "
                + "AND actor_id = ? AND user_id = ? AND metadata::text LIKE '%restore%'",
            Long.class, admin.userId(), member.userId()))
            .as("a new SecurityEventType constant would need a migration widening V9's CHECK, and "
                + "keeping the deletion and the restoration of one account under one type is also "
                + "how they stay adjacent in an incident review")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("Restoring after the grace has passed is 409 conflict")
    void restoringAfterTheGraceIsRefused() {
        Session admin = freshHousehold("restore-late-admin");
        Session member = inviteAndRegister(admin, "restore-late-member");
        delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken());
        backdateDeletion(member.userId(), "8 days");

        HttpResponse<String> refused = restore(admin, member.userId());

        assertThat(refused.statusCode()).as("%s", refused.body()).isEqualTo(409);
        assertThat(Json.errorCode(refused.body())).isEqualTo("conflict");
        assertThat(Json.parse(refused.body()).path("error").path("detail").asString())
            .as("reactivating an account that is queued for erasure is a promise the server "
                + "cannot keep, and the message has to say why")
            .contains("restore window");
    }

    @Test
    @DisplayName("Restoring a member who was never deleted is 409, not a silent success")
    void restoringALiveMemberIsRefused() {
        Session admin = freshHousehold("restore-live-admin");
        Session member = inviteAndRegister(admin, "restore-live-member");

        HttpResponse<String> refused = restore(admin, member.userId());

        assertThat(refused.statusCode()).as("%s", refused.body()).isEqualTo(409);
        assertThat(Json.parse(refused.body()).path("error").path("detail").asString())
            .as("answering 200 would tell an administrator they had undone something they had not")
            .contains("nothing to restore");
    }

    @Test
    @DisplayName("A member of another household is 404, and an ordinary member is refused on role")
    void restoreIsScopedAndAdminOnly() {
        Session adminA = freshHousehold("restore-scope-a");
        Session memberA = inviteAndRegister(adminA, "restore-scope-a-member");
        Session adminB = freshHousehold("restore-scope-b");
        delete(appUrl("/v1/me"), Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + memberA.accessToken());

        assertThat(restore(adminB, memberA.userId()).statusCode())
            .as("the lookup is household-scoped, so 'not yours' and 'does not exist' are the same "
                + "answer and neither confirms an id is real")
            .isEqualTo(404);

        Session ordinary = inviteAndRegister(adminA, "restore-scope-a-ordinary");
        assertThat(restore(ordinary, memberA.userId()).statusCode())
            .as("/v1/admin/** is role-gated before any id is read")
            .isEqualTo(403);
        assertThat(postJson(restoreUrl(memberA.userId()), null).statusCode())
            .as("and it needs a session at all")
            .isEqualTo(401);
    }

    @Test
    @DisplayName("An admin's own delete is the same soft delete, and is restorable")
    void theAdminDeleteRouteIsSoftToo() {
        // One path, one guard, one event: keeping the admin delete hard while the self delete was
        // soft would be two behaviours a client had to explain, and the last-ADMIN guard would
        // have to be right in two places.
        Session admin = freshHousehold("restore-admin-route");
        Session member = inviteAndRegister(admin, "restore-admin-route-member");

        assertThat(delete(appUrl("/v1/admin/users/" + member.userId()), null,
            "Authorization", "Bearer " + admin.accessToken()).statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT is_active FROM users WHERE id = ?", Boolean.class, member.userId()))
            .isFalse();
        assertThat(Json.elements(Json.data(
                get(appUrl("/v1/admin/users"), admin.accessToken()).body())))
            .as("GET /v1/admin/users is where the administrator who has to decide whether to "
                + "restore them looks, so the member must still be listed")
            .anySatisfy(node -> {
                assertThat(node.path("id").asString()).isEqualTo(member.userId().toString());
                assertThat(node.path("active").asBoolean()).isFalse();
            });

        assertThat(restore(admin, member.userId()).statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT is_active FROM users WHERE id = ?", Boolean.class, member.userId())).isTrue();
    }

    @Test
    @DisplayName("Deleting a member who is already inside the grace is 409, not a second delete")
    void deletingAnAlreadyDeletedMemberIsRefused() {
        // Without this refusal a second DELETE answers 200 and overwrites the stamp: the
        // seven-day clock restarts, the erasure is pushed back another week, `deleted_by` loses
        // who actually removed them, and a second ACCOUNT_DELETE is written for a deletion that
        // already happened — repeatable indefinitely, so an administrator could defer an erasure
        // for ever while the API reported success every time. restoreMember refuses the
        // symmetric no-op for the same reason.
        Session admin = freshHousehold("double-delete-admin");
        Session member = inviteAndRegister(admin, "double-delete-member");
        String memberUrl = appUrl("/v1/admin/users/" + member.userId());

        assertThat(delete(memberUrl, null, "Authorization", "Bearer " + admin.accessToken())
            .statusCode()).isEqualTo(200);
        Instant firstStamp = jdbcTemplate.queryForObject(
            "SELECT deleted_at FROM users WHERE id = ?", Instant.class, member.userId());
        long eventsAfterTheFirst = deleteEventsFor(member.userId());

        HttpResponse<String> second = delete(memberUrl, null,
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(second.statusCode()).as("%s", second.body()).isEqualTo(409);
        assertThat(Json.errorCode(second.body())).isEqualTo("conflict");
        assertThat(Json.parse(second.body()).path("error").path("detail").asString())
            .contains("already been deleted");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT deleted_at FROM users WHERE id = ?", Instant.class, member.userId()))
            .as("the purge clock must not be restartable by asking again")
            .isEqualTo(firstStamp);
        assertThat(deleteEventsFor(member.userId()))
            .as("and no second ACCOUNT_DELETE for a deletion that already happened")
            .isEqualTo(eventsAfterTheFirst);
        assertThat(restore(admin, member.userId()).statusCode())
            .as("the refusal changed nothing, so the restore still works")
            .isEqualTo(200);
    }

    private long deleteEventsFor(UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE type = 'ACCOUNT_DELETE' "
                + "AND metadata::text LIKE ?", Long.class, "%" + userId + "%");
    }
}
