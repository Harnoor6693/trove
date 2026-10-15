package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code audit_log} and {@code GET /v1/audit} (§4.2; gap P2-17, "D7 fixed").
 *
 * <p>The pre-collapse gateway wrote this table on every request and nothing ever read it — no
 * route exposed it, so an audit trail existed only as a storage cost. Two things are proved here:
 * that the rows are written at all, and that what lands in them is safe to keep.
 */
class AuditLogIT extends AuthFlowSupport {

    /**
     * The write is asynchronous by design — §4.2 requires the row after the response, never before
     * it — so a test that read the table immediately would be racing the executor. Polling states
     * the asynchrony instead of hiding it behind a sleep that is either flaky or slow.
     */
    private List<Map<String, Object>> awaitRows(String sql, Object... args) {
        await("an audit row for " + sql,
            () -> !jdbcTemplate.queryForList(sql, args).isEmpty());
        return jdbcTemplate.queryForList(sql, args);
    }

    /** Polls for up to ten seconds; fails with {@code what} rather than hanging or sleeping. */
    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + what, e);
            }
        }
        throw new AssertionError("Timed out after 10s waiting for " + what
            + ". The audit write is asynchronous by design (§4.2: after the response, never "
            + "before it), so this is either a very slow executor or a write that never happened.");
    }

    @Test
    @DisplayName("A mutating request writes one row carrying who, what, whence and how long")
    void oneRowPerMutatingRequest() {
        Session admin = loginBootstrapAdmin();

        HttpResponse<String> response = putJson(appUrl("/v1/admin/settings"),
            Json.object("registrationOpen", false),
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(response.statusCode()).isEqualTo(200);
        UUID requestId = UUID.fromString(response.headers().firstValue("X-Request-Id").orElseThrow());

        Map<String, Object> row = awaitRows(
            "SELECT method, path, status, user_id, household_id, host(client_ip) AS client_ip, "
                + "duration_ms FROM audit_log WHERE request_id = ?", requestId).get(0);

        assertThat(row.get("method")).isEqualTo("PUT");
        assertThat(row.get("path")).isEqualTo("/api/v1/admin/settings");
        assertThat(row.get("status")).isEqualTo(200);
        assertThat(row.get("user_id")).isEqualTo(admin.userId());
        assertThat(row.get("household_id"))
            .as("the scoping column of GET /v1/audit; without it an admin reads every household")
            .isEqualTo(admin.householdId());
        assertThat(row.get("client_ip"))
            .as("resolved through the trusted-proxy valve like every other address in this "
                + "application, never taken from the header by hand")
            .isEqualTo(clientAddress());
        assertThat((Long) row.get("duration_ms")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("The path stored is the route template, with the id erased")
    void theIdInThePathIsNeverStored() {
        Session admin = loginBootstrapAdmin();
        String inviteId = Json.data(postJson(appUrl("/v1/admin/invites"), "{}",
            "Authorization", "Bearer " + admin.accessToken()).body()).path("id").asString();

        HttpResponse<String> revoked = delete(appUrl("/v1/admin/invites/" + inviteId), null,
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        UUID requestId =
            UUID.fromString(revoked.headers().firstValue("X-Request-Id").orElseThrow());

        assertThat(awaitRows("SELECT path FROM audit_log WHERE request_id = ?", requestId)
            .get(0).get("path"))
            .as("§4.2: route template only, never the raw URI. Two routes on this API carry a "
                + "live credential in the path, and a table of raw URIs would be a permanent "
                + "plaintext copy of every invitation token the install has issued — exactly "
                + "what V9_2 encrypts the token column to prevent")
            .isEqualTo("/api/v1/admin/invites/{id}");

        assertThat(jdbcTemplate.queryForList("SELECT path FROM audit_log"))
            .as("and no row anywhere holds the id itself, or a query string")
            .allSatisfy(row -> assertThat((String) row.get("path"))
                .doesNotContain(inviteId)
                .doesNotContain("?"));
    }

    @Test
    @DisplayName("Reads are not audited — the table answers what changed, not what was looked at")
    void readsAreNotAudited() {
        Session admin = loginBootstrapAdmin();

        HttpResponse<String> read = get(appUrl("/v1/admin/users"), admin.accessToken());
        UUID requestId = UUID.fromString(read.headers().firstValue("X-Request-Id").orElseThrow());

        assertThat(jdbcTemplate.queryForList(
            "SELECT id FROM audit_log WHERE request_id = ?", requestId))
            .as("reads would dominate the table by an order of magnitude and §4.2's question is "
                + "\"what changed, and who changed it\"")
            .isEmpty();
    }

    @Test
    @DisplayName("An anonymous mutation is recorded with a null user rather than dropped")
    void anonymousMutationsAreRecorded() {
        HttpResponse<String> login = postJson(appUrl("/v1/auth/login"),
            Json.object("email", uniqueEmail("audit-anon"), "password", "a-wrong-passphrase"));
        UUID requestId = UUID.fromString(login.headers().firstValue("X-Request-Id").orElseThrow());

        Map<String, Object> row = awaitRows(
            "SELECT user_id, status, path FROM audit_log WHERE request_id = ?", requestId).get(0);

        assertThat(row.get("user_id"))
            .as("a failed sign-in has no principal and is exactly the row an incident review "
                + "wants; dropping it would leave the interesting half of the traffic unrecorded")
            .isNull();
        assertThat(row.get("status")).isEqualTo(401);
        assertThat(row.get("path")).isEqualTo("/api/v1/auth/login");
    }

    @Test
    @DisplayName("A refused mutation is recorded with the status the caller received")
    void refusalsAreRecordedWithTheirStatus() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "audit-refused");

        HttpResponse<String> refused = putJson(appUrl("/v1/admin/settings"),
            Json.object("registrationOpen", true),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(refused.statusCode()).isEqualTo(403);
        UUID requestId =
            UUID.fromString(refused.headers().firstValue("X-Request-Id").orElseThrow());

        assertThat(awaitRows("SELECT status FROM audit_log WHERE request_id = ?", requestId)
            .get(0).get("status"))
            .as("an audit trail that recorded only successes would omit every attempt worth "
                + "reviewing")
            .isEqualTo(403);
    }

    @Test
    @DisplayName("GET /v1/audit returns the household's own rows, newest first, with page metadata")
    void theAdminCanReadTheLog() {
        Session admin = loginBootstrapAdmin();
        putJson(appUrl("/v1/admin/settings"), Json.object("registrationOpen", false),
            "Authorization", "Bearer " + admin.accessToken());
        awaitRows("SELECT id FROM audit_log WHERE household_id = ?", admin.householdId());

        HttpResponse<String> response =
            get(appUrl("/v1/audit?page=0&limit=10"), admin.accessToken());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        JsonNode body = Json.parse(response.body());
        List<JsonNode> rows = Json.elements(body.path("data"));
        assertThat(rows).isNotEmpty().hasSizeLessThanOrEqualTo(10);
        assertThat(body.path("meta").path("limit").asInt()).isEqualTo(10);
        assertThat(body.path("meta").path("page").asInt()).isZero();
        assertThat(body.path("meta").path("total").asLong())
            .as("the totals a client renders its page controls from, in the same fetch that "
                + "rendered the rows")
            .isGreaterThanOrEqualTo(rows.size());

        assertThat(rows).allSatisfy(row -> {
            assertThat(row.path("method").asString())
                .isIn("POST", "PUT", "PATCH", "DELETE");
            assertThat(row.path("path").asString()).startsWith("/api/");
            assertThat(row.has("clientIp")).isTrue();
            assertThat(row.has("requestId")).isTrue();
        });
    }

    @Test
    @DisplayName("Paging is stable: page 1 does not repeat page 0")
    void pagingDoesNotRepeatRows() {
        Session admin = loginBootstrapAdmin();
        for (int i = 0; i < 3; i++) {
            putJson(appUrl("/v1/admin/settings"), Json.object("registrationOpen", false),
                "Authorization", "Bearer " + admin.accessToken());
        }
        await("four audited mutations for this household", () -> jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE household_id = ?", Long.class,
            admin.householdId()) >= 4L);

        List<JsonNode> first = Json.elements(Json.parse(
            get(appUrl("/v1/audit?page=0&limit=2"), admin.accessToken()).body()).path("data"));
        List<JsonNode> second = Json.elements(Json.parse(
            get(appUrl("/v1/audit?page=1&limit=2"), admin.accessToken()).body()).path("data"));

        assertThat(first).hasSize(2);
        assertThat(second).isNotEmpty();
        assertThat(second.stream().map(row -> row.path("id").asString()).toList())
            .as("ordering by created_at alone lets two rows written in the same microsecond — "
                + "which a burst produces — appear on both pages or on neither")
            .doesNotContainAnyElementsOf(first.stream().map(r -> r.path("id").asString()).toList());
    }

    @Test
    @DisplayName("A page or limit outside its range is refused, not clamped")
    void queryBoundsAreEnforced() {
        Session admin = loginBootstrapAdmin();

        HttpResponse<String> negativePage = withoutRequestValidation(
            "page=-1 is below the schema's minimum, which is the point: the server must refuse it",
            () -> get(appUrl("/v1/audit?page=-1"), admin.accessToken()));
        assertThat(negativePage.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(negativePage.body())).isEqualTo("validation-error");

        HttpResponse<String> hugeLimit = withoutRequestValidation(
            "limit=5000 is above the schema's maximum; a clamped answer would leave a client "
                + "believing it had asked for and received five thousand rows",
            () -> get(appUrl("/v1/audit?limit=5000"), admin.accessToken()));
        assertThat(hugeLimit.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(hugeLimit.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("An ordinary member cannot read the household's audit log")
    void membersCannotReadTheLog() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "audit-member");

        assertThat(get(appUrl("/v1/audit"), member.accessToken()).statusCode())
            .as("the log names every administrative action in the household; a member who could "
                + "read it could watch the admin")
            .isEqualTo(403);
        assertThat(get(appUrl("/v1/audit")).statusCode())
            .as("and it is not anonymous")
            .isEqualTo(401);
    }
}
