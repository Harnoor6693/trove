package finance.trove.auth;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The append-only {@code security_event} log actually gets appended to (gap P2-17).
 *
 * <p>This suite exists because {@code SecurityEventRecorder} deliberately swallows database
 * errors: an unavailable audit table must not turn a successful login into a 500, and must not
 * turn a failed one into a success either. The consequence is that <strong>the only way a
 * missing row is ever noticed is a test that reads the table</strong>. Without these assertions
 * the log can keep "working" while recording nothing, which is the worst failure mode an
 * evidence trail has.
 *
 * <p>It caught exactly that during collapse step 3: {@code INVITE_ACCEPTED} was written inside
 * the registering transaction with {@code REQUIRES_NEW}, so its own transaction could not see the
 * uncommitted user, the foreign key to {@code users} failed, and every acceptance was dropped
 * with only an ERROR line to show for it.
 */
class SecurityEventIT extends AuthFlowSupport {

    private List<Map<String, Object>> eventsFor(UUID userId) {
        return jdbcTemplate.queryForList(
            "SELECT type, actor_id, ip, ua_summary, metadata::text AS metadata "
                + "FROM security_event WHERE user_id = ? ORDER BY created_at", userId);
    }

    private List<String> typesFor(UUID userId) {
        return eventsFor(userId).stream().map(row -> (String) row.get("type")).toList();
    }

    @Test
    @DisplayName("Accepting an invite writes INVITE_ACCEPTED after the registration commits")
    void inviteAcceptanceIsRecorded() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "event-invite");

        assertThat(typesFor(member.userId()))
            .as("written with recordAfterCommit; a REQUIRES_NEW write here cannot see the "
                + "uncommitted user and is dropped on the foreign key")
            .contains("INVITE_ACCEPTED");
    }

    @Test
    @DisplayName("Minting an invite writes INVITE_CREATED against the admin who did it")
    void inviteCreationIsRecorded() {
        Session admin = loginBootstrapAdmin();
        int before = typesFor(admin.userId()).stream()
            .filter("INVITE_CREATED"::equals).toList().size();

        createInvite(admin, null);

        assertThat(typesFor(admin.userId()).stream()
            .filter("INVITE_CREATED"::equals).toList())
            .hasSizeGreaterThan(before);
    }

    @Test
    @DisplayName("A successful login writes LOGIN_OK with the factor used")
    void successfulLoginIsRecorded() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "event-login");

        loginBearer(member.email(), MEMBER_PASSWORD);

        assertThat(eventsFor(member.userId()))
            .anySatisfy(row -> {
                assertThat(row.get("type")).isEqualTo("LOGIN_OK");
                assertThat((String) row.get("metadata")).contains("password");
            });
    }

    @Test
    @DisplayName("A rejected login writes LOGIN_FAIL and survives the caller's rollback")
    void failedLoginIsRecorded() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "event-fail");

        HttpResponse<String> rejected = postJson(appUrl("/v1/auth/login"),
            Json.object("email", member.email(), "password", "the-wrong-passphrase-entirely"));
        assertThat(rejected.statusCode()).isEqualTo(401);

        assertThat(typesFor(member.userId()))
            .as("the authenticate() transaction throws and rolls back; REQUIRES_NEW is what "
                + "keeps the evidence")
            .contains("LOGIN_FAIL");
    }

    @Test
    @DisplayName("A failed login for an unknown address is recorded with a null subject")
    void failedLoginForAnUnknownAddressIsRecorded() {
        Long before = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE type = 'LOGIN_FAIL' AND user_id IS NULL",
            Long.class);

        postJson(appUrl("/v1/auth/login"),
            Json.object("email", uniqueEmail("ghost"), "password", "does-not-matter-at-all"));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE type = 'LOGIN_FAIL' AND user_id IS NULL",
            Long.class))
            .as("an attempt at an address that resolves to nobody must still be counted, or the "
                + "per-address gate cannot see credential stuffing")
            .isGreaterThan(before);
    }

    @Test
    @DisplayName("Logging out writes SESSION_REVOKE")
    void logoutIsRecorded() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "event-logout");

        postJson(appUrl("/v1/auth/logout"),
            Json.object("refreshToken", member.refreshToken()));

        assertThat(typesFor(member.userId())).contains("SESSION_REVOKE");
    }

    @Test
    @DisplayName("Rows carry the caller's address and a truncated user agent, never a credential")
    void rowsCarryContextAndNoSecrets() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "event-context");
        loginBearer(member.email(), MEMBER_PASSWORD);

        assertThat(eventsFor(member.userId()))
            .isNotEmpty()
            .allSatisfy(row -> {
                assertThat(row.get("ip"))
                    .as("from getRemoteAddr(), never a hand-parsed X-Forwarded-For (gap P2-5)")
                    .isNotNull();
                String metadata = (String) row.get("metadata");
                if (metadata != null) {
                    assertThat(metadata)
                        .as("no password, token, TOTP secret or recovery code may ever land here")
                        .doesNotContain(MEMBER_PASSWORD)
                        .doesNotContain(member.accessToken())
                        .doesNotContain(member.refreshToken());
                }
            });
    }
}
