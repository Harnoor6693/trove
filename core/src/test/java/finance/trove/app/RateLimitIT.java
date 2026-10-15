package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.ratelimit.RateLimitRules;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The request-rate ceiling over HTTP (§5.8, gap P2-5).
 *
 * <h2>How a limit is reached without ten thousand requests</h2>
 * The shipped seeds are sized for a household, not for a test: reaching {@code /api/v1/auth/**} at
 * 60 per minute would take a minute of argon2id. So each test inserts one deliberately tiny rule
 * for the route it is about, reloads, and takes it out again — which also exercises the thing V12
 * exists for, that limits live in a table an admin can edit on a running install, rather than in a
 * constant nobody can change without a deploy.
 *
 * <p>Each test method already arrives from its own client address ({@code BaseIT}), so a bucket
 * spent here cannot refuse a later test, and the anonymous cases are genuinely isolated from one
 * another.
 */
class RateLimitIT extends AuthFlowSupport {

    @Autowired
    private RateLimitRules rules;

    /**
     * The shipped rules, taken before this class edits them and put back afterwards.
     *
     * <p>Snapshot-and-restore rather than "delete the rows I added": every test here replaces a
     * pattern, and one of them replaces {@code /api/v1/auth/login} — a seeded row. Leaving that at
     * a burst of one would refuse the sign-in of every IT class that ran afterwards, and the
     * failure would surface somewhere else entirely.
     */
    private List<Map<String, Object>> shippedRules;

    @BeforeEach
    void rememberShippedRules() {
        shippedRules = jdbcTemplate.queryForList(
            "SELECT endpoint_pattern, requests_per_min, burst_capacity FROM rate_limit_config");
        assertThat(shippedRules)
            .as("V12 seeds this table; an empty one means the migration did not run")
            .isNotEmpty();
    }

    @AfterEach
    void restoreShippedRules() {
        jdbcTemplate.update("DELETE FROM rate_limit_config");
        for (Map<String, Object> rule : shippedRules) {
            jdbcTemplate.update(
                "INSERT INTO rate_limit_config "
                    + "(endpoint_pattern, requests_per_min, burst_capacity) VALUES (?, ?, ?)",
                rule.get("endpoint_pattern"), rule.get("requests_per_min"),
                rule.get("burst_capacity"));
        }
        rules.reload();
    }

    /** Installs a rule and makes it live, exactly as an admin edit plus the reload job would. */
    private void limit(String pattern, int perMinute, int burst) {
        jdbcTemplate.update("DELETE FROM rate_limit_config WHERE endpoint_pattern = ?", pattern);
        jdbcTemplate.update(
            "INSERT INTO rate_limit_config (endpoint_pattern, requests_per_min, burst_capacity) "
                + "VALUES (?, ?, ?)", pattern, perMinute, burst);
        rules.reload();
    }

    @Test
    @DisplayName("An anonymous caller burning its burst is refused with 429 rate-limited")
    void anonymousCallersAreLimited() {
        limit("/api/v1/auth/providers", 1, 3);

        for (int i = 1; i <= 3; i++) {
            assertThat(get(appUrl("/v1/auth/providers")).statusCode())
                .as("request %s is inside the burst", i)
                .isEqualTo(200);
        }

        HttpResponse<String> refused = get(appUrl("/v1/auth/providers"));

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(Json.errorCode(refused.body()))
            .as("a distinct slug from account-locked: the two mean different things and both "
                + "clients switch on the slug")
            .isEqualTo("rate-limited");
        assertThat(refused.headers().firstValue("Retry-After"))
            .as("without it the only strategy left to a client is to retry immediately, which is "
                + "what produced the 429")
            .hasValue("60");
    }

    @Test
    @DisplayName("A bearer caller is limited too, and on their own bucket")
    void bearerCallersAreLimited() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "ratelimit-bearer");
        limit("/api/v1/auth/me", 1, 2);

        assertThat(get(appUrl("/v1/auth/me"), member.accessToken()).statusCode()).isEqualTo(200);
        assertThat(get(appUrl("/v1/auth/me"), member.accessToken()).statusCode()).isEqualTo(200);
        assertThat(get(appUrl("/v1/auth/me"), member.accessToken()).statusCode())
            .as("the limiter runs after the JWT filter, so an authenticated caller is keyed on "
                + "their user id rather than falling into a flat anonymous bucket — §5.1's "
                + "correction of the original filter order")
            .isEqualTo(429);

        assertThat(get(appUrl("/v1/auth/me"), admin.accessToken()).statusCode())
            .as("a different account, the same address and the same route: the bucket is the "
                + "user's, so the member exhausting theirs must not refuse the admin")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("GET /v1/status is never limited, even with a rule that names it")
    void theStatusRouteIsExempt() {
        limit("/api/v1/status", 1, 1);

        for (int i = 1; i <= 5; i++) {
            assertThat(get(appUrl("/v1/status")).statusCode())
                .as("the SPA's degraded-state banner and any load balancer poll this "
                    + "continuously; a rule that could refuse it would take the install down "
                    + "from the outside in")
                .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("A refusal happens before the handler runs — no work is done for a refused call")
    void aRefusedRequestNeverReachesTheHandler() {
        limit("/api/v1/auth/login", 1, 1);
        String email = uniqueEmail("ratelimit-login");

        assertThat(postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", "wrong-passphrase-here")).statusCode())
            .as("the first call is inside the burst and is answered on its merits")
            .isEqualTo(401);

        HttpResponse<String> refused = postJson(appUrl("/v1/auth/login"),
            Json.object("email", email, "password", "wrong-passphrase-here"));
        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(Json.errorCode(refused.body())).isEqualTo("rate-limited");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM login_attempt WHERE email_hash = ?", Long.class,
            finance.trove.auth.local.LoginAttemptRepository.hashEmail(email)))
            .as("the refused call cost no argon2id verification and left no attempt row, which "
                + "is the whole point of a limiter in front of the sign-in surface")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("The most specific rule is the one that applies")
    void theMostSpecificRuleApplies() {
        limit("/api/v1/auth/providers", 1, 2);

        assertThat(get(appUrl("/v1/auth/providers")).statusCode()).isEqualTo(200);
        assertThat(get(appUrl("/v1/auth/providers")).statusCode()).isEqualTo(200);
        assertThat(get(appUrl("/v1/auth/providers")).statusCode())
            .as("/api/v1/auth/** at 60 a minute also matches this path; the tighter, longer "
                + "pattern has to win or a seeded per-route limit is unreachable")
            .isEqualTo(429);

        assertThat(get(appUrl("/v1/auth/invites/does-not-exist")).statusCode())
            .as("a sibling route under the broader /api/v1/auth/** rule is untouched. It answers "
                + "200 with the verdict in the body by design, so that an invalid invite token "
                + "cannot be told from a valid one by its status")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("A percent-encoded spelling of a route lands in the same bucket as the canonical "
        + "one")
    void anEncodedSpellingSharesTheBucket() {
        limit("/api/v1/auth/providers", 1, 1);

        assertThat(get(appUrl("/v1/auth/providers")).statusCode())
            .as("the burst of one is spent by the canonical spelling")
            .isEqualTo(200);

        assertThat(get(appUrl("/v1/auth/provider%73")).statusCode())
            .as("Tomcat decodes before it routes, so this reaches the same handler; matched "
                + "against the RAW URI it would miss /api/v1/auth/providers and fall to the "
                + "wildcard rule instead — a client picking its own, larger, ceiling by spelling "
                + "one letter as an escape")
            .isEqualTo(429);
    }

    @Test
    @DisplayName("Two addresses do not share an anonymous bucket")
    void anonymousBucketsArePerAddress() {
        limit("/api/v1/auth/providers", 1, 1);

        assertThat(getWith(appUrl("/v1/auth/providers"), FORWARDED_FOR, "203.0.113.11")
            .statusCode()).isEqualTo(200);
        assertThat(getWith(appUrl("/v1/auth/providers"), FORWARDED_FOR, "203.0.113.11")
            .statusCode()).isEqualTo(429);
        assertThat(getWith(appUrl("/v1/auth/providers"), FORWARDED_FOR, "203.0.113.12")
            .statusCode())
            .as("the address comes from the trusted-proxy valve, so one client cannot spend "
                + "another's allowance and cannot escape its own by rewriting a header")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("An edit to rate_limit_config takes effect on the next reload, with no restart")
    void limitsAreEditableAtRuntime() {
        limit("/api/v1/auth/providers", 1, 1);
        assertThat(get(appUrl("/v1/auth/providers")).statusCode()).isEqualTo(200);
        assertThat(get(appUrl("/v1/auth/providers")).statusCode()).isEqualTo(429);

        limit("/api/v1/auth/providers", 60, 120);

        assertThat(get(appUrl("/v1/auth/providers")).statusCode())
            .as("this is what the table is for: an operator retunes a limit that is hurting a "
                + "household without a deploy, and the reload job picks it up")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("The seeded rules really are loaded, so nothing above passed against an empty set")
    void theSeedsAreInForce() {
        assertThat(rules.current())
            .as("every assertion in this class would hold vacuously against a limiter with no "
                + "rules at all")
            .isNotEmpty()
            .anySatisfy(rule -> assertThat(rule.pattern()).isEqualTo("/api/**"));
        assertThat(rules.current().get(0).pattern().length())
            .as("held most-specific-first, so the first match wins")
            .isGreaterThanOrEqualTo(rules.current().get(rules.current().size() - 1)
                .pattern().length());
    }
}
