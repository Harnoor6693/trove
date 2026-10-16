package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.app.support.RowLockGate;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Password reset and password change (readiness gaps P2-8 and P2-6).
 *
 * <p>Reset here is not an email, because most self-hosted installs have no mail server: it is an
 * admin minting a one-hour single-use link and handing it over out of band. The properties that
 * matter are that the link dies on first use, that issuing a second one kills the first, and that
 * redeeming ends every session — a reset is what somebody does when they believe their password is
 * known to another person, so leaving that person's sessions alive would defeat the exercise.
 */
class PasswordResetIT extends AuthFlowSupport {

    private static final String NEW_PASSWORD = "a-different-fine-passphrase";

    private String issueLink(Session admin, String email) {
        HttpResponse<String> response = postJson(appUrl("/v1/auth/password/reset-request"),
            Json.object("email", email), "Authorization", "Bearer " + admin.accessToken());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode data = Json.data(response.body());
        assertThat(data.path("url").asString()).startsWith(PUBLIC_URL + "/reset-password/");
        return data.path("url").asString().substring(
            (PUBLIC_URL + "/reset-password/").length());
    }

    @Test
    @DisplayName("An admin mints a link, the member redeems it and can sign in with the new one")
    void theHappyPath() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "reset-happy");

        String token = issueLink(admin, member.email());
        HttpResponse<String> redeemed = postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", token, "password", NEW_PASSWORD));

        assertThat(redeemed.statusCode()).as(redeemed.body()).isEqualTo(200);
        assertThat(loginBearer(member.email(), NEW_PASSWORD).accessToken()).isNotBlank();
        assertThat(postJson(appUrl("/v1/auth/login"),
            Json.object("email", member.email(), "password", MEMBER_PASSWORD)).statusCode())
            .isEqualTo(401);
    }

    @Test
    @DisplayName("Redeeming ends every session the account had")
    void redeemingRevokesEverySession() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "reset-revoke");
        Session secondDevice = loginBearer(member.email(), MEMBER_PASSWORD);

        postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", issueLink(admin, member.email()), "password", NEW_PASSWORD));

        for (Session dead : new Session[] {member, secondDevice}) {
            assertThat(postJson(appUrl("/v1/auth/refresh"),
                Json.object("refreshToken", dead.refreshToken())).statusCode())
                .as("whoever else had the old password must not still be logged in")
                .isEqualTo(401);
            assertThat(get(appUrl("/v1/auth/me"), dead.accessToken()).statusCode())
                .as("and not for the next fifteen minutes either: revoking the refresh row alone "
                    + "left the ACCESS token that was already issued from it working on every "
                    + "route until its own expiry, which is the whole window a reset exists to "
                    + "close")
                .isEqualTo(401);
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE user_id = ? AND type = 'PASSWORD_RESET'",
            Long.class, member.userId()))
            .isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("A link is single-use")
    void linksAreSingleUse() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "reset-once");
        String token = issueLink(admin, member.email());

        assertThat(postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", token, "password", NEW_PASSWORD)).statusCode()).isEqualTo(200);
        assertThat(postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", token, "password", "yet-another-fine-passphrase")).statusCode())
            .isEqualTo(400);
    }

    @Test
    @DisplayName("One link, two simultaneous redeems: exactly one password is set")
    void aLinkCannotBeRedeemedTwiceConcurrently() throws Exception {
        // Redeem was check-then-act with no lock: read consumed_at, hash a password for tens of
        // milliseconds, then write consumed_at. Two requests could both read null, and whoever
        // committed last chose the final password, so an attacker racing the owner won.
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "reset-race");
        String token = issueLink(admin, member.email());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> results;
        // Both redeems must write the member's credential row. Holding it open until both are
        // parked means each has already passed whatever check it makes before that write.
        RowLockGate gate = RowLockGate.hold(jdbcTemplate,
            "SELECT 1 FROM local_credential WHERE user_id = ? FOR UPDATE", member.userId());
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            results = List.of(
                pool.submit(() -> redeemRace(ready, go, token, NEW_PASSWORD)),
                pool.submit(() -> redeemRace(ready, go, token, "an-attackers-fine-passphrase")));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            gate.awaitWaiters(2, Duration.ofSeconds(30));
        } finally {
            gate.close();
        }

        try {
            List<Integer> statuses = List.of(results.get(0).get(60, TimeUnit.SECONDS),
                results.get(1).get(60, TimeUnit.SECONDS));
            assertThat(statuses)
                .as("a single-use link sets one password: %s", statuses)
                .containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
    }

    private int redeemRace(CountDownLatch ready, CountDownLatch go, String token,
                           String password) throws Exception {
        ready.countDown();
        go.await(30, TimeUnit.SECONDS);
        return postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", token, "password", password)).statusCode();
    }

    @Test
    @DisplayName("A link for a deactivated account is refused like an unknown one")
    void aDeactivatedAccountCannotBeReset() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "reset-inactive");
        String token = issueLink(admin, member.email());
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", member.userId());

        HttpResponse<String> refused = postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", token, "password", NEW_PASSWORD));

        assertThat(refused.statusCode())
            .as("a reset must not rewrite the password of a member who was removed or "
                + "disabled after the link was minted: %s", refused.body())
            .isEqualTo(400);
        assertThat(Json.errorCode(refused.body())).isEqualTo("reset-invalid");
    }

    @Test
    @DisplayName("Issuing a second link invalidates the first")
    void issuingAgainInvalidatesTheFirst() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "reset-reissue");

        String first = issueLink(admin, member.email());
        String second = issueLink(admin, member.email());

        assertThat(postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", first, "password", NEW_PASSWORD)).statusCode())
            .as("an admin who reissues because the first was mistyped must not leave two live "
                + "resets on one account")
            .isEqualTo(400);
        assertThat(postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", second, "password", NEW_PASSWORD)).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("An expired link is refused with the same wording as an unknown one")
    void expiredLinksAreRefused() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "reset-expired");
        String token = issueLink(admin, member.email());
        jdbcTemplate.update(
            "UPDATE password_reset_token SET expires_at = now() - interval '1 minute' "
                + "WHERE token_hash = ?",
            finance.trove.auth.reset.PasswordResetService.hash(token));

        HttpResponse<String> expired = postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", token, "password", NEW_PASSWORD));
        HttpResponse<String> unknown = postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", "never-issued-at-all", "password", NEW_PASSWORD));

        assertThat(expired.statusCode()).isEqualTo(unknown.statusCode()).isEqualTo(400);
        assertThat(Json.parse(expired.body()).path("error").path("detail").asString())
            .isEqualTo(Json.parse(unknown.body()).path("error").path("detail").asString());
    }

    @Test
    @DisplayName("The new password still goes through the policy, and a refusal keeps the link")
    void theNewPasswordIsStillPolicyChecked() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "reset-policy");
        String token = issueLink(admin, member.email());

        HttpResponse<String> refused = postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", token, "password", "password1234"));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(refused.body())).isEqualTo("password-policy");

        assertThat(postJson(appUrl("/v1/auth/password/reset"),
            Json.object("token", token, "password", NEW_PASSWORD)).statusCode())
            .as("burning the link on a policy failure would force the member to ask for another")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("Only an admin may mint a reset link")
    void mintingIsAdminOnly() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "reset-notadmin");

        assertThat(postJson(appUrl("/v1/auth/password/reset-request"),
            Json.object("email", member.email()),
            "Authorization", "Bearer " + member.accessToken()).statusCode())
            .as("a public route that mints reset links, with no second channel to deliver them "
                + "through, would simply be a reset oracle")
            .isEqualTo(403);
        assertThat(postJson(appUrl("/v1/auth/password/reset-request"),
            Json.object("email", member.email())).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("Changing a password needs the current one and ends every other session")
    void changingAPasswordNeedsTheCurrentOne() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "change-password");
        Session otherDevice = loginBearer(member.email(), MEMBER_PASSWORD);

        HttpResponse<String> wrong = postJson(appUrl("/v1/auth/password"),
            Json.object("currentPassword", "not-the-current-one", "newPassword", NEW_PASSWORD),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(wrong.statusCode()).isEqualTo(403);
        assertThat(Json.errorCode(wrong.body())).isEqualTo("step-up-required");

        HttpResponse<String> changed = postJson(appUrl("/v1/auth/password"),
            Json.object("currentPassword", MEMBER_PASSWORD, "newPassword", NEW_PASSWORD),
            "Authorization", "Bearer " + member.accessToken(), AuthMode.HEADER, "bearer");
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        assertThat(Json.data(changed.body()).path("user").path("id").asString())
            .as("the response carries the user and nothing else")
            .isEqualTo(member.userId().toString());
        assertThat(Json.data(changed.body()).path("accessToken").asString(""))
            .as("no replacement token: the caller's own pair still works, and re-issuing one "
                + "would put a live credential in a second place for no benefit")
            .isEmpty();

        assertThat(postJson(appUrl("/v1/auth/refresh"),
            Json.object("refreshToken", otherDevice.refreshToken())).statusCode())
            .as("the reason somebody changes a password is usually that they think another "
                + "person has it")
            .isEqualTo(401);
        assertThat(get(appUrl("/v1/auth/me"), otherDevice.accessToken()).statusCode())
            .as("\"loses it immediately\" is what the javadoc promises, and it is the access "
                + "token that a thief actually holds; revoking only the refresh row left them "
                + "with full read/write access for the rest of its fifteen minutes")
            .isEqualTo(401);
        assertThat(postJson(appUrl("/v1/auth/refresh"),
            Json.object("refreshToken", member.refreshToken())).statusCode())
            .as("the device that just proved it knows the password keeps its session; revoking "
                + "it signed mobile out ~15 min later, since that client types this call "
                + "Promise<void> and treats a rejected refresh as terminal")
            .isEqualTo(200);
        assertThat(get(appUrl("/v1/auth/me"), member.accessToken()).statusCode())
            .as("the family check spares the caller's own family, which is the entire reason the "
                + "fid claim exists rather than a user-level cut-off")
            .isEqualTo(200);
        assertThat(loginBearer(member.email(), NEW_PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("A new password that fails the policy is refused with a field error")
    void changingToAWeakPasswordIsRefused() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "change-weak");

        HttpResponse<String> response = postJson(appUrl("/v1/auth/password"),
            Json.object("currentPassword", MEMBER_PASSWORD, "newPassword", "short"),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("password-policy");
        assertThat(Json.elements(Json.parse(response.body()).path("error").path("fieldErrors")))
            .anySatisfy(error -> assertThat(error.path("field").asString()).isEqualTo("password"));
    }
}
