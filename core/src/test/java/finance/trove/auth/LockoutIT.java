package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.auth.totp.TotpGenerator;
import java.net.http.HttpResponse;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Readiness gap P2-5 over HTTP: the account lock, the per-address gate, and the promise that
 * neither is visible to the caller.
 *
 * <p><strong>Why this class truncates {@code login_attempt}.</strong> Every integration test in
 * the suite connects from {@code 127.0.0.1}, so the per-address counter is shared by the whole
 * run. Without cleaning up, this class would push the address over the threshold and every login
 * in every later IT class would answer 429 — a failure that would look like a bug in whatever ran
 * next. Cleaning before as well as after means it also cannot be broken by whatever ran before.
 */
class LockoutIT extends AuthFlowSupport {

    @BeforeEach
    @AfterEach
    void clearAttempts() {
        jdbcTemplate.update("DELETE FROM login_attempt");
    }

    private HttpResponse<String> attempt(String email, String password) {
        return postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", password));
    }

    /** Enrols {@code member} in TOTP through the real setup/confirm endpoints. */
    private String enrolTotp(Session member) {
        TotpGenerator generator = new TotpGenerator();
        String secret = Json.data(postJson(appUrl("/v1/auth/totp/setup"),
            Json.object("password", MEMBER_PASSWORD),
            "Authorization", "Bearer " + member.accessToken()).body())
            .path("secret").asString();
        HttpResponse<String> confirmed = postJson(appUrl("/v1/auth/totp/confirm"),
            Json.object("code",
                generator.codeAt(secret, generator.timeStep(Instant.now(), 30), 6)),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        return secret;
    }

    @Test
    @DisplayName("Wrong TOTP codes are counted, and the account locks like any other guessing")
    void theSecondFactorIsBruteForceProtectedToo() {
        // Six digits is a million guesses in principle and rather fewer in practice: the server
        // accepts a window of three steps, and an attacker who already has the password can mint
        // a fresh sixty-second challenge whenever they like. Before this, completeTotpLogin
        // logged a LOGIN_FAIL and counted nothing at all — the password half of the login was
        // throttled and the half that is supposed to be the stronger one was not.
        Session member = inviteAndRegister(loginBootstrapAdmin(), "lockout-totp");
        enrolTotp(member);

        for (int i = 0; i < 5; i++) {
            String challenge = Json.data(attempt(member.email(), MEMBER_PASSWORD).body())
                .path("challengeToken").asString();
            assertThat(challenge).as("the password half still succeeds").isNotBlank();
            assertThat(postJson(appUrl("/v1/auth/login/totp"),
                Json.object("challengeToken", challenge, "code", "000000")).statusCode())
                .isEqualTo(401);
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM login_attempt WHERE success = false", Long.class))
            .as("every wrong code is an attempt on this account, recorded like a wrong password")
            .isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked_until FROM local_credential WHERE user_id = ?",
            java.sql.Timestamp.class, member.userId()))
            .as("REQUIRES_NEW: the 401 that follows must not roll the counter back")
            .isNotNull();

        HttpResponse<String> afterwards = attempt(member.email(), MEMBER_PASSWORD);
        assertThat(afterwards.statusCode())
            .as("the account is now locked, and the right password no longer gets a challenge")
            .isEqualTo(401);
        assertThat(Json.errorCode(afterwards.body())).isEqualTo("credentials-invalid");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE user_id = ? AND type = 'LOCKOUT'",
            Long.class, member.userId()))
            .isGreaterThan(0L);
    }

    @Test
    @DisplayName("A locked account cannot finish a login through the second factor either")
    void aLockedAccountCannotCompleteTheSecondFactor() {
        // The lock lives on local_credential and was read in exactly one place:
        // LocalAccountService.authenticate, the password half of the login. completeTotpLogin
        // checked nothing but User::isActive, so a challenge minted in the sixty seconds before
        // the account locked still completed into a full session — and the account locks
        // precisely when somebody is guessing at it.
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "lockout-totp-gate");
        String secret = enrolTotp(member);

        String challenge = Json.data(attempt(member.email(), MEMBER_PASSWORD).body())
            .path("challengeToken").asString();
        assertThat(challenge).as("a live challenge, taken while the account was healthy")
            .isNotBlank();

        for (int i = 0; i < 5; i++) {
            attempt(member.email(), "wrong-passphrase-" + i);
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked_until FROM local_credential WHERE user_id = ?",
            java.sql.Timestamp.class, member.userId()))
            .as("the account is locked before the probe below, or it proves nothing")
            .isNotNull();

        // One step ahead, because the replay guard is real: confirmEnrolment recorded the step it
        // accepted a moment ago, and the same six digits inside that thirty-second window are
        // refused as a replay. Within the ±1-step drift window it is a valid code now.
        TotpGenerator generator = new TotpGenerator();
        String code = generator.codeAt(secret, generator.timeStep(Instant.now(), 30) + 1, 6);
        HttpResponse<String> refused = postJson(appUrl("/v1/auth/login/totp"),
            Json.object("challengeToken", challenge, "code", code));

        assertThat(refused.statusCode())
            .as("a right code on a live challenge, and the account is locked: %s", refused.body())
            .isEqualTo(401);
        assertThat(Json.errorCode(refused.body()))
            .as("the same slug the password half answers, so the lock stays invisible here too")
            .isEqualTo("credentials-invalid");

        // And the proof that the refusal above was the lock rather than a stale challenge or a
        // wrong code: clear the lock and present exactly the same two values again.
        HttpResponse<String> unlocked = postJson(
            appUrl("/v1/admin/users/" + member.userId() + "/unlock"), null,
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(unlocked.statusCode()).as(unlocked.body()).isEqualTo(200);

        HttpResponse<String> accepted = postJson(appUrl("/v1/auth/login/totp"),
            Json.object("challengeToken", challenge, "code", code));
        assertThat(accepted.statusCode())
            .as("same challenge, same code, lock gone: %s", accepted.body())
            .isEqualTo(200);
    }

    @Test
    @DisplayName("Five wrong passwords lock the account, and the lock is invisible")
    void theAccountLocksAndSaysNothingAboutIt() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "lockout-account");

        HttpResponse<String> firstFailure = attempt(member.email(), "wrong-passphrase-one");
        for (int i = 0; i < 4; i++) {
            attempt(member.email(), "wrong-passphrase-" + i);
        }

        HttpResponse<String> withTheRightPassword = attempt(member.email(), MEMBER_PASSWORD);

        assertThat(withTheRightPassword.statusCode())
            .as("five failures inside the window soft-lock the account")
            .isEqualTo(401);
        assertThat(Json.errorCode(withTheRightPassword.body()))
            .isEqualTo(Json.errorCode(firstFailure.body()))
            .isEqualTo("credentials-invalid");
        assertThat(Json.parse(withTheRightPassword.body()).path("error").path("detail").asString())
            .as("the response never distinguishes locked-out from wrong-password, or the lock "
                + "message becomes confirmation that a real account was found")
            .isEqualTo(Json.parse(firstFailure.body()).path("error").path("detail").asString());

        assertThat(jdbcTemplate.queryForObject(
            "SELECT locked_until FROM local_credential WHERE user_id = ?",
            java.sql.Timestamp.class, member.userId()))
            .as("the lock itself is recorded, it is simply never reported")
            .isNotNull();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE user_id = ? AND type = 'LOCKOUT'",
            Long.class, member.userId()))
            .isGreaterThan(0L);
    }

    @Test
    @DisplayName("An unknown address and a wrong password are the same response, byte for byte")
    void enumerationSafety() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "lockout-enum");

        HttpResponse<String> unknown = attempt(uniqueEmail("nobody"), "some-passphrase-here");
        HttpResponse<String> wrong = attempt(member.email(), "some-passphrase-here");

        assertThat(unknown.statusCode()).isEqualTo(wrong.statusCode()).isEqualTo(401);

        var unknownError = Json.parse(unknown.body()).path("error");
        var wrongError = Json.parse(wrong.body()).path("error");
        assertThat(unknownError.path("type").asString()).isEqualTo(wrongError.path("type").asString());
        assertThat(unknownError.path("title").asString())
            .isEqualTo(wrongError.path("title").asString());
        assertThat(unknownError.path("detail").asString())
            .as("body, status and slug are identical; only requestId and instance differ, and "
                + "both are per-request values that carry no information about the account")
            .isEqualTo(wrongError.path("detail").asString());
        assertThat(unknownError.has("fieldErrors")).isEqualTo(wrongError.has("fieldErrors"));
    }

    @Test
    @DisplayName("An account lock does not leak through a different address either")
    void lockedAccountsLookIdenticalFromAnywhere() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "lockout-nonleak");
        for (int i = 0; i < 5; i++) {
            attempt(member.email(), "wrong-passphrase-" + i);
        }

        HttpResponse<String> locked = attempt(member.email(), MEMBER_PASSWORD);
        HttpResponse<String> unknown = attempt(uniqueEmail("nobody"), MEMBER_PASSWORD);

        assertThat(Json.parse(locked.body()).path("error").path("detail").asString())
            .isEqualTo(Json.parse(unknown.body()).path("error").path("detail").asString());
    }

    @Test
    @DisplayName("Twenty failures from one address answer 429 account-locked")
    void thePerAddressGateTrips() {
        Session admin = loginBootstrapAdmin();
        clearAttempts();

        HttpResponse<String> lastAllowed = null;
        for (int i = 0; i < 20; i++) {
            lastAllowed = attempt(uniqueEmail("stuffing"), "a-guess-number-" + i);
            assertThat(lastAllowed.statusCode())
                .as("attempt %s must still be answered as an ordinary failure", i)
                .isEqualTo(401);
        }

        HttpResponse<String> refused = attempt(uniqueEmail("stuffing"), "one-guess-too-many");

        assertThat(refused.statusCode())
            .as("credential stuffing is one guess each against a thousand addresses, which trips "
                + "no per-account counter anywhere; this is the control that sees it")
            .isEqualTo(429);
        assertThat(Json.errorCode(refused.body()))
            .as("the slug both clients switch on for this state, and the only path that reaches "
                + "it — an account lock is deliberately indistinguishable from a wrong password")
            .isEqualTo("account-locked");
        assertThat(refused.headers().firstValue("Retry-After"))
            .as("openapi/trove-core.yaml's LoginThrottled response declares Retry-After and says "
                + "it is sent with every 429 on this operation. Only RateLimitFilter used to set "
                + "one, so this branch — the one a client is most likely to back off from — "
                + "answered with nothing to back off by. The value is the counting window, which "
                + "is when the oldest failure being counted falls out of it")
            .hasValue(String.valueOf(java.time.Duration.ofMinutes(10).toSeconds()));
    }

    @Test
    @DisplayName("The address gate also blocks a login that would otherwise have succeeded")
    void thePerAddressGateRunsBeforeAnyCredentialIsRead() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "lockout-gate");
        clearAttempts();
        for (int i = 0; i < 20; i++) {
            attempt(uniqueEmail("stuffing"), "a-guess-number-" + i);
        }

        assertThat(attempt(member.email(), MEMBER_PASSWORD).statusCode())
            .as("the gate runs before anything reads a credential, so a stuffing run does not "
                + "get an argon2id verification per guess")
            .isEqualTo(429);
    }

    @Test
    @DisplayName("A successful login clears the account's counter")
    void successResetsTheCounter() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "lockout-reset");

        for (int i = 0; i < 3; i++) {
            attempt(member.email(), "wrong-passphrase-" + i);
        }
        assertThat(attempt(member.email(), MEMBER_PASSWORD).statusCode()).isEqualTo(200);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT failed_attempts FROM local_credential WHERE user_id = ?",
            Integer.class, member.userId()))
            .isZero();
    }

    @Test
    @DisplayName("An admin can unlock a member without changing their password")
    void adminUnlock() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "lockout-unlock");
        for (int i = 0; i < 5; i++) {
            attempt(member.email(), "wrong-passphrase-" + i);
        }
        assertThat(attempt(member.email(), MEMBER_PASSWORD).statusCode()).isEqualTo(401);

        HttpResponse<String> unlocked = postJson(
            appUrl("/v1/admin/users/" + member.userId() + "/unlock"), null,
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(unlocked.statusCode()).as(unlocked.body()).isEqualTo(200);

        assertThat(attempt(member.email(), MEMBER_PASSWORD).statusCode())
            .as("the alternative for a member who mistyped ten times is waiting a day, and for "
                + "an admin a database edit")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("Every failed attempt is recorded, including ones at addresses that do not exist")
    void everyAttemptIsRecorded() {
        clearAttempts();

        attempt(uniqueEmail("ghost"), "a-guess");
        attempt(BOOTSTRAP_ADMIN_EMAIL, "another-guess");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM login_attempt WHERE success = false", Long.class))
            .as("per-address counting has to see guesses at addresses that do not exist, or the "
                + "cheapest evasion is to guess at addresses that do not")
            .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForList("SELECT email_hash FROM login_attempt"))
            .as("the address itself is never stored: this table is written on every failure and "
                + "would otherwise become a list of every address anyone has guessed here")
            .allSatisfy(row -> assertThat((String) row.get("email_hash")).hasSize(64));
    }
}
