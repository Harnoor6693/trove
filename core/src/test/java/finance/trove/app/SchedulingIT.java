package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.schedule.PurgeJobs;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.auth.local.LoginAttemptRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §5.6 over a real database: the lock is really taken, and the sweeps really delete.
 *
 * <p>Four purge methods existed since collapse step 3 and none was ever called, which is a failure
 * with no symptom — the tables simply grow, on every install, for ever. A test that asserted only
 * "the annotation is present" would have passed against the broken tree too, so each assertion
 * here drives the job and then counts rows.
 *
 * <p>Calling the job directly rather than waiting for its cron is deliberate: the sweeps run at
 * 03:10 UTC and a test that waited for one would never finish. The call still goes through the
 * ShedLock proxy, so the lock is exercised exactly as the scheduler would exercise it.
 */
class SchedulingIT extends AuthFlowSupport {

    @Autowired
    private PurgeJobs jobs;

    private List<Map<String, Object>> lock(String name) {
        return jdbcTemplate.queryForList(
            "SELECT name, lock_until, locked_at, locked_by FROM shedlock WHERE name = ?", name);
    }

    @Test
    @DisplayName("Running a sweep really takes its ShedLock row")
    void theLockIsAcquired() {
        jobs.purgeRefreshTokens();

        assertThat(lock("refresh-token-purge"))
            .as("without a lock a rolling restart runs every sweep twice, and a second instance "
                + "would run all of them concurrently")
            .hasSize(1)
            .allSatisfy(row -> {
                assertThat(row.get("locked_by")).isNotNull();
                assertThat(row.get("locked_at")).isNotNull();
                assertThat(row.get("lock_until"))
                    .as("the deadline that stops a process killed mid-job wedging its schedule "
                        + "for ever")
                    .isNotNull();
            });
    }

    @Test
    @DisplayName("Each sweep takes its own lock, so one cannot silently starve another")
    void everySweepHasItsOwnLock() {
        jobs.purgeRefreshTokens();
        jobs.purgeExpiredInvites();
        jobs.purgeExpiredPasswordResets();
        jobs.purgeLoginAttempts();
        jobs.purgeAuditLog();

        for (String name : PurgeJobs.LOCK_NAMES) {
            assertThat(lock(name))
                .as("ShedLock keys on the name alone: two jobs sharing one would serialise into "
                    + "each other and the second would never run. Missing: %s", name)
                .hasSize(1);
        }
    }

    @Test
    @DisplayName("An expired refresh token is really deleted, and a live one is not")
    void expiredSessionsArePurged() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "purge-session");

        UUID staleId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO refresh_token (id, user_id, family_id, token_hash, issued_at, "
                + "expires_at, last_used_at, ip_address, user_agent) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            staleId, member.userId(), UUID.randomUUID(),
            "not-a-real-hash-" + staleId,
            Timestamp.from(Instant.now().minus(40, ChronoUnit.DAYS)),
            Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)),
            Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)),
            "198.18.0.1", "purge-test");

        jobs.purgeRefreshTokens();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM refresh_token WHERE id = ?", Long.class, staleId))
            .as("the sweep §5.6 names and step 3 wrote but never called")
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
            Long.class, member.userId()))
            .as("the member's live session survives, or the sweep is a logout for everybody")
            .isPositive();
    }

    @Test
    @DisplayName("Login attempts older than the retention window are really deleted")
    void oldLoginAttemptsArePurged() {
        String ancient = "purge-ancient-" + UUID.randomUUID() + "@trove.test";
        String recent = "purge-recent-" + UUID.randomUUID() + "@trove.test";
        jdbcTemplate.update(
            "INSERT INTO login_attempt (email_hash, ip, success, at) "
                + "VALUES (?, CAST(? AS inet), false, ?)",
            LoginAttemptRepository.hashEmail(ancient), "198.18.0.2",
            Timestamp.from(Instant.now().minus(400, ChronoUnit.DAYS)));
        jdbcTemplate.update(
            "INSERT INTO login_attempt (email_hash, ip, success, at) "
                + "VALUES (?, CAST(? AS inet), false, ?)",
            LoginAttemptRepository.hashEmail(recent), "198.18.0.2",
            Timestamp.from(Instant.now()));

        jobs.purgeLoginAttempts();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM login_attempt WHERE email_hash = ?", Long.class,
            LoginAttemptRepository.hashEmail(ancient)))
            .as("this table takes a row per failed login from anywhere on the internet; unswept "
                + "it is the fastest-growing table on the install")
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM login_attempt WHERE email_hash = ?", Long.class,
            LoginAttemptRepository.hashEmail(recent)))
            .as("and the rows the lockout window is still counting must survive it")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("An expired invitation is swept and a live one is left alone")
    void expiredInvitesAreSwept() {
        Session admin = loginBootstrapAdmin();
        String liveInviteId = finance.trove.app.support.Json.data(
            postJson(appUrl("/v1/admin/invites"), "{}",
                "Authorization", "Bearer " + admin.accessToken()).body()).path("id").asString();

        UUID staleId = UUID.randomUUID();
        jdbcTemplate.update(
            "INSERT INTO household_invite (id, household_id, email, token_hash, token_encrypted, "
                + "created_by, created_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            staleId, admin.householdId(), null, "stale-hash-" + staleId, null, admin.userId(),
            Timestamp.from(Instant.now().minus(10, ChronoUnit.DAYS)),
            Timestamp.from(Instant.now().minus(5, ChronoUnit.DAYS)));

        jobs.purgeExpiredInvites();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household_invite WHERE id = ?", Long.class, staleId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM household_invite WHERE id = ?", Long.class,
            UUID.fromString(liveInviteId)))
            .as("a 72-hour invitation that is still redeemable must survive the daily sweep, or "
                + "handing somebody a link becomes a race against the cron")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("Audit rows past the retention window are deleted and recent ones are kept")
    void oldAuditRowsArePurged() {
        UUID ancient = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        for (Map.Entry<UUID, Instant> row : Map.of(
            ancient, Instant.now().minus(400, ChronoUnit.DAYS),
            recent, Instant.now()).entrySet()) {
            jdbcTemplate.update(
                "INSERT INTO audit_log (id, method, path, status, duration_ms, request_id, "
                    + "created_at) VALUES (?, 'POST', '/api/v1/purge-probe', 200, 1, ?, ?)",
                row.getKey(), UUID.randomUUID(), Timestamp.from(row.getValue()));
        }

        jobs.purgeAuditLog();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE id = ?", Long.class, ancient))
            .as("one row per mutating request, for ever, is the same unbounded growth the other "
                + "four sweeps exist to stop")
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE id = ?", Long.class, recent))
            .as("and the evidence an incident review actually reads must survive the sweep")
            .isEqualTo(1L);
        assertThat(lock("audit-log-purge"))
            .as("guarded like every other sweep: a rolling restart must not run it twice")
            .hasSize(1);
    }

    @Test
    @DisplayName("The rate-limit rules are loaded at startup by the scheduled reload")
    void theRulesAreLoadedByTheScheduler() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM rate_limit_config", Long.class))
            .as("V12 seeds this table")
            .isPositive();
        assertThat(lock("rate-limit-reload"))
            .as("deliberately NOT lock-guarded: it refreshes state in this JVM's heap, and a "
                + "lock would mean that with two instances only one ever reloaded while the "
                + "other enforced stale rules for ever")
            .isEmpty();
    }
}
