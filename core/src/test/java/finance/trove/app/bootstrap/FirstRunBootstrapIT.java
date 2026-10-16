package finance.trove.app.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.auth.AuthProperties;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.local.PasswordPolicyException;
import finance.trove.common.time.TimeProperties;
import finance.trove.households.HouseholdRepository;
import finance.trove.users.UserRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Readiness gap P2-7's first boot, asserted against the install this suite actually runs on.
 *
 * <p>Nothing here seeds anything. {@code BaseIT} supplies {@code TROVE_ADMIN_EMAIL} and
 * {@code TROVE_ADMIN_PASSWORD} and the first context to start bootstrapped from them, exactly as a
 * real first boot does — so the state under test was produced by the production code path rather
 * than by a fixture. That is also why every other IT in the suite can sign in at all.
 */
class FirstRunBootstrapIT extends AuthFlowSupport {

    @Test
    @DisplayName("The bootstrap created the install's original household")
    void theBootstrapHouseholdExists() {
        Session admin = loginBootstrapAdmin();

        // Not "exactly one household": AccessControlIT creates a second one by SQL to prove
        // isolation, and the IT database is shared across the whole run. What the bootstrap owns
        // is the ORIGINAL household — the oldest row — and this asserts the admin landed in it.
        assertThat(jdbcTemplate.queryForObject(
            "SELECT id FROM households ORDER BY created_at ASC LIMIT 1", java.util.UUID.class))
            .as("owner decision: one shared household ledger per install (§1.5); the bootstrap "
                + "creates it and the first administrator belongs to it")
            .isEqualTo(admin.householdId());
    }

    @Test
    @DisplayName("The bootstrapped account is an ADMIN with a usable argon2id credential")
    void theAdminExistsAndCanSignIn() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT u.id, u.role, u.email, u.display_name, u.currency, u.country, u.timezone, "
                + "u.locale, u.is_active, c.password_hash "
                + "FROM users u JOIN local_credential c ON c.user_id = u.id "
                + "WHERE lower(u.email) = ?", BOOTSTRAP_ADMIN_EMAIL);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.get("role")).isEqualTo("ADMIN");
            assertThat(row.get("display_name")).isEqualTo("Admin");
            assertThat(row.get("currency")).isEqualTo("CAD");
            assertThat(row.get("country")).isEqualTo("CA");
            assertThat(row.get("locale")).isEqualTo("en-CA");
            assertThat(row.get("is_active")).isEqualTo(true);
            assertThat((String) row.get("password_hash"))
                .as("argon2id at the configured cost, with the parameters in the PHC string so a "
                    + "later re-tune re-hashes rather than invalidating every password")
                .startsWith("$argon2id$v=19$")
                .contains("m=65536")
                .contains("t=3")
                .contains("p=1");
        });

        Session admin = loginBootstrapAdmin();
        assertThat(Json.data(get(appUrl("/v1/auth/me"), admin.accessToken()).body())
            .path("role").asString()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("Registration is closed on the household the bootstrap created")
    void registrationStartsClosed() {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT registration_open FROM households ORDER BY created_at ASC LIMIT 1",
            Boolean.class))
            .as("an open /register on the public internet lets strangers create accounts on a "
                + "household's finance server; the env var is only a seed and defaults false")
            .isFalse();
    }

    @Test
    @DisplayName("Running the bootstrap again is a no-op, decided by data and not by a marker")
    void reRunningChangesNothing() {
        Long householdsBefore =
            jdbcTemplate.queryForObject("SELECT count(*) FROM households", Long.class);
        Long usersBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM users", Long.class);
        // Counts are compared before and after rather than to a fixed number, so this stays true
        // whatever else the shared IT database already holds.
        String hashBefore = jdbcTemplate.queryForObject(
            "SELECT c.password_hash FROM local_credential c JOIN users u ON u.id = c.user_id "
                + "WHERE lower(u.email) = ?", String.class, BOOTSTRAP_ADMIN_EMAIL);

        bootstrap.bootstrapIfEmpty(java.time.Instant.now());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM households", Long.class))
            .isEqualTo(householdsBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Long.class))
            .isEqualTo(usersBefore);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT c.password_hash FROM local_credential c JOIN users u ON u.id = c.user_id "
                + "WHERE lower(u.email) = ?", String.class, BOOTSTRAP_ADMIN_EMAIL))
            .as("leaving TROVE_ADMIN_PASSWORD in the environment is normal; re-applying it on "
                + "every restart would silently undo a password the owner has since changed")
            .isEqualTo(hashBefore);
    }

    @Test
    @DisplayName("A refused admin password rolls the household and user back (CI-42)")
    void aRefusedPasswordLeavesNothingBehind() {
        // The shared IT database already has users, so the real bootstrap is a no-op here. Build
        // one over the real repositories with the "install is empty" check forced, which is the
        // only way to drive the first-run writes against a real transaction manager.
        UserRepository emptyLooking = Mockito.mock(UserRepository.class,
            AdditionalAnswers.delegatesTo(users));
        Mockito.doReturn(0L).when(emptyLooking).countAllUsers();
        FirstRunBootstrap firstRun = new FirstRunBootstrap(
            new BootstrapProperties("rollback-probe@trove.test", "short", "Rollback Probe Home",
                null),
            authProperties, timeProperties, households, emptyLooking, localAccounts,
            transactionManager);

        assertThatThrownBy(() -> firstRun.bootstrapIfEmpty(java.time.Instant.now()))
            .isInstanceOf(PasswordPolicyException.class);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM households WHERE name = 'Rollback Probe Home'", Long.class))
            .as("the household must not survive a refused credential: the next boot would see "
                + "users, never retry, and leave an administrator with no password")
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM users WHERE lower(email) = 'rollback-probe@trove.test'",
            Long.class)).isZero();
    }

    @Autowired
    private FirstRunBootstrap bootstrap;

    @Autowired
    private UserRepository users;

    @Autowired
    private HouseholdRepository households;

    @Autowired
    private LocalAccountService localAccounts;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private TimeProperties timeProperties;

    @Autowired
    private PlatformTransactionManager transactionManager;
}
