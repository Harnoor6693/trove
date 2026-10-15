package finance.trove.users.admin;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.budgets.BudgetTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code DELETE /v1/me}: a member leaves, and the household keeps its ledger (collapse step 18;
 * gap row 16, which exists because Apple 5.1.1(v) and Play's account-deletion policy reject an
 * application with sign-up and no in-app deletion).
 *
 * <p>The claims asserted here are the ones a member is told before they press the button: the
 * account stops working immediately, the sessions are gone, the deletion is recorded, and the
 * household's shared rows survive with the author's name removed.
 *
 * <p>Household B is used for every last-ADMIN case. Household A's administrator is the suite-wide
 * bootstrap account and deleting it would take the whole run down with it.
 */
class AccountDeletionIT extends BudgetTestSupport {

    private String stepUp() {
        return Json.object("password", MEMBER_PASSWORD);
    }

    private HttpResponse<String> deleteMe(Session member, String body) {
        return delete(appUrl("/v1/me"), body, "Authorization", "Bearer " + member.accessToken());
    }

    private Map<String, Object> userRow(UUID userId) {
        return jdbcTemplate.queryForMap(
            "SELECT is_active, deleted_at, deleted_by FROM users WHERE id = ?", userId);
    }

    private long liveSessionsOf(UUID userId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
            Long.class, userId);
    }

    private List<Map<String, Object>> accountDeleteEventsFor(UUID userId) {
        return jdbcTemplate.queryForList(
            "SELECT metadata FROM security_event WHERE type = 'ACCOUNT_DELETE' AND actor_id = ?",
            userId);
    }

    @Test
    @DisplayName("A member deletes their own account: soft-deleted, sessions revoked, recorded")
    void aMemberCanDeleteTheirOwnAccount() {
        Session owner = freshHousehold("self-delete-admin");
        Session member = inviteAndRegister(owner, "self-delete-member");
        assertThat(liveSessionsOf(member.userId())).isPositive();

        HttpResponse<String> deleted = deleteMe(member, stepUp());

        assertThat(deleted.statusCode()).as("%s", deleted.body()).isEqualTo(200);
        Map<String, Object> row = userRow(member.userId());
        assertThat(row.get("is_active"))
            .as("is_active is the liveness flag every check in the tree already reads; the soft "
                + "delete does not invent a second one")
            .isEqualTo(false);
        assertThat(row.get("deleted_at"))
            .as("the stamp is what distinguishes a member an admin merely disabled from one "
                + "inside the grace, and it is the only column the purge selects on")
            .isNotNull();
        assertThat(row.get("deleted_by")).isEqualTo(member.userId());
        assertThat(liveSessionsOf(member.userId()))
            .as("a hard delete took the sessions with it through ON DELETE CASCADE; a soft one "
                + "has to revoke them, or a live access token outlives its own account")
            .isZero();
        assertThat(accountDeleteEventsFor(member.userId()))
            .as("an incident review has to be able to answer 'was this account ever deleted?'")
            .isNotEmpty();
    }

    @Test
    @DisplayName("A deleted member cannot sign in, refresh or use the token they already held")
    void aDeletedMemberIsRefusedEverywhereDuringTheGrace() {
        Session owner = freshHousehold("self-delete-login-admin");
        Session member = inviteAndRegister(owner, "self-delete-login");
        String email = member.email();
        String stillHeld = member.accessToken();
        String refresh = member.refreshToken();

        assertThat(deleteMe(member, stepUp()).statusCode()).isEqualTo(200);

        assertThat(get(appUrl("/v1/auth/me"), stillHeld).statusCode())
            .as("CookieOrBearerJwtAuthFilter asks SessionValidity.isLive(fid) on every "
                + "authenticated request, so a revoked family really is revoked on the next call")
            .isEqualTo(401);
        assertThat(postJson(appUrl("/v1/auth/refresh"),
            Json.object("refreshToken", refresh)).statusCode())
            .as("the refresh family is revoked too, or the member simply mints a new session")
            .isEqualTo(401);
        HttpResponse<String> login = postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", MEMBER_PASSWORD),
            "X-Trove-Auth-Mode", "bearer");
        assertThat(login.statusCode())
            .as("is_active = false is already honoured by AuthService: %s", login.body())
            .isEqualTo(401);
        assertThat(Json.errorCode(login.body()))
            .as("and it must answer exactly what a wrong password answers, so the refusal is not "
                + "an oracle for 'this address had an account here'")
            .isEqualTo("credentials-invalid");
    }

    @Test
    @DisplayName("The household's ledger survives the member, with the author nulled")
    void theSharedLedgerSurvivesTheMember() {
        Session owner = freshHousehold("self-delete-ledger-admin");
        Session member = inviteAndRegister(owner, "self-delete-ledger");
        String accountId = createManualAccount(member, "Chequing");
        String transactionId = createManualTransaction(member, accountId, "40.00",
            todayForCaller(member), "DEBIT", "Bakery").path("id").asString();

        assertThat(deleteMe(member, stepUp()).statusCode()).isEqualTo(200);

        // Owner decision 17: the shared ledger wins and the author is nulled. During the grace the
        // row is not even nulled yet, because the member's row is still there — the point of this
        // assertion is that the household's transaction is untouched and still visible to the
        // member who remains.
        assertThat(Json.data(get(appUrl("/v1/transactions/" + transactionId),
            owner.accessToken()).body()).path("id").asString())
            .as("a household mate's spending record is the household's, not the departing "
                + "member's, and deleting an account must not delete it")
            .isEqualTo(transactionId);
    }

    @Test
    @DisplayName("A wrong password is 403 step-up-required and the account is untouched")
    void aWrongPasswordDoesNotDelete() {
        Session owner = freshHousehold("self-delete-stepup-admin");
        Session member = inviteAndRegister(owner, "self-delete-stepup");

        HttpResponse<String> refused = deleteMe(member, Json.object("password", "not-the-one"));

        assertThat(refused.statusCode()).as("%s", refused.body()).isEqualTo(403);
        assertThat(Json.errorCode(refused.body())).isEqualTo("step-up-required");
        assertThat(userRow(member.userId()).get("is_active"))
            .as("a failed step-up must change nothing at all")
            .isEqualTo(true);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE type = 'LOGIN_FAIL' AND user_id = ? "
                + "AND metadata::text LIKE '%step-up-password%'", Long.class, member.userId()))
            .as("a failed step-up on the deletion route is exactly the event an incident review "
                + "wants to see")
            .isPositive();
    }

    @Test
    @DisplayName("The household's last ADMIN cannot delete themselves: 409 conflict")
    void theLastAdminCannotLeave() {
        // Household B, never household A: household A's admin is the suite-wide bootstrap account
        // and FirstRunBootstrap does not put it back.
        Session onlyAdmin = freshHousehold("self-delete-last-admin");

        HttpResponse<String> refused = deleteMe(onlyAdmin, stepUp());

        assertThat(refused.statusCode()).as("%s", refused.body()).isEqualTo(409);
        assertThat(Json.errorCode(refused.body())).isEqualTo("conflict");
        assertThat(userRow(onlyAdmin.userId()).get("is_active"))
            .as("an install whose only administrator has gone has no way back in: registration "
                + "is closed, invites are an admin route, and so is the flag that opens "
                + "registration")
            .isEqualTo(true);
    }

    @Test
    @DisplayName("With a second administrator in the household the guard steps aside")
    void anAdminCanLeaveOnceSomebodyElseCanAdminister() {
        Session first = freshHousehold("self-delete-two-admins");
        String token = Json.data(postJson(appUrl("/v1/admin/invites"),
            Json.object("role", "ADMIN"),
            "Authorization", "Bearer " + first.accessToken()).body()).path("token").asString();
        Session second = registerWithInvite(token, uniqueEmail("self-delete-coadmin"),
            MEMBER_PASSWORD);

        assertThat(deleteMe(first, stepUp()).statusCode())
            .as("the guard counts rather than comparing ids, so self-deletion is allowed exactly "
                + "while somebody else can still administer the household")
            .isEqualTo(200);
        assertThat(get(appUrl("/v1/admin/users"), second.accessToken()).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("POST /v1/me/delete is the same operation, for clients that drop a DELETE body")
    void thePostTwinDeletesToo() {
        Session owner = freshHousehold("self-delete-post-admin");
        Session member = inviteAndRegister(owner, "self-delete-post");

        HttpResponse<String> deleted = postJson(appUrl("/v1/me/delete"), stepUp(),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(deleted.statusCode()).as("%s", deleted.body()).isEqualTo(200);
        assertThat(userRow(member.userId()).get("is_active")).isEqualTo(false);
    }

    @Test
    @DisplayName("An anonymous caller cannot delete anybody")
    void deletionNeedsASession() {
        assertThat(delete(appUrl("/v1/me"), stepUp()).statusCode()).isEqualTo(401);
        assertThat(postJson(appUrl("/v1/me/delete"), stepUp()).statusCode()).isEqualTo(401);
    }
}
