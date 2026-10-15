package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.common.storage.LocalDiskStorage;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A run belongs to the member who asked for it, and to nobody else.
 *
 * <p><strong>This class asserts the opposite of every other ledger IT in the tree.</strong>
 * Everywhere else a household mate must see a household mate's rows, because the ledger is shared.
 * Here they must not: a tax filing is individual (§1.5, §6), so a mate asking for another member's
 * run gets the same 404 a stranger does. An implementation that scoped this table by
 * {@code household_id} — the rule steps 6-10 follow — would fail every test below, which is why
 * they exist.
 */
class TaxExportRunIT extends LedgerSupport {

    private static final int TAX_YEAR = 2002;
    private static final String RUNS = "/v1/tax-export/runs";

    @Autowired
    private LocalDiskStorage storage;

    private Session owner;
    private Session householdMate;

    @BeforeEach
    void twoMembersOfOneHousehold() {
        Session admin = loginBootstrapAdmin();
        owner = inviteAndRegister(admin, "tax-run-owner");
        householdMate = inviteAndRegister(admin, "tax-run-mate");
        assertThat(householdMate.householdId())
            .as("these two share a ledger; the point of this class is that they do not share a "
                + "filing")
            .isEqualTo(owner.householdId());
    }

    private String startAndComplete() {
        HttpResponse<String> created = postJson(appUrl(RUNS),
            Json.object("taxYear", TAX_YEAR, "format", "CSV"),
            "Authorization", "Bearer " + owner.accessToken());
        assertThat(created.statusCode()).as("%s", created.body()).isEqualTo(202);
        String runId = Json.data(created.body()).path("id").asString();

        for (int attempt = 0; attempt < 120; attempt++) {
            JsonNode run = Json.data(
                get(appUrl(RUNS + "/" + runId), owner.accessToken()).body());
            String status = run.path("status").asString();
            if ("COMPLETE".equals(status)) {
                return runId;
            }
            assertThat(status)
                .as("run failed: %s", run.path("errorMessage").asString(""))
                .isNotEqualTo("FAILED");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while polling", e);
            }
        }
        throw new AssertionError("run " + runId + " never reached COMPLETE");
    }

    /**
     * A run that is guaranteed to be PENDING.
     *
     * <p>Inserted directly, because the only other way to observe a PENDING run is to beat the
     * background executor to it, and a test that races a thread pool reports a scheduling
     * accident as a defect. Everything the test then exercises — the route, the ownership check,
     * the status check — is production code.
     */
    private UUID pendingRunOf(Session session) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO tax_export_run (user_id, tax_year, jurisdiction, format, status) "
                + "VALUES (?, ?, 'CA', 'CSV', 'PENDING') RETURNING id",
            UUID.class, session.userId(), TAX_YEAR);
    }

    @Test
    @DisplayName("A household mate gets 404 on GET, DELETE and download")
    void aHouseholdMateCannotReachAnotherMembersRun() {
        String runId = startAndComplete();
        String mate = "Bearer " + householdMate.accessToken();

        HttpResponse<String> read = getWithHeader(appUrl(RUNS + "/" + runId), "Authorization",
            mate);
        HttpResponse<String> download = getWithHeader(
            appUrl(RUNS + "/" + runId + "/download"), "Authorization", mate);
        HttpResponse<String> removed = delete(appUrl(RUNS + "/" + runId), null,
            "Authorization", mate);

        assertThat(read.statusCode()).as("%s", read.body()).isEqualTo(404);
        assertThat(download.statusCode()).as("%s", download.body()).isEqualTo(404);
        assertThat(removed.statusCode()).as("%s", removed.body()).isEqualTo(404);
        assertThat(Json.errorCode(read.body()))
            .as("404 and not 403: a 403 would confirm the run exists")
            .isEqualTo("not-found");
    }

    @Test
    @DisplayName("A household mate's run is not in this member's list")
    void aHouseholdMatesRunIsNotListed() {
        String runId = startAndComplete();

        HttpResponse<String> response = get(appUrl(RUNS), householdMate.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(response.body())).stream()
            .map(row -> row.path("id").asString()).toList())
            .as("this is deliberately NOT assertHouseholdMatesShareVisibility: a filing is "
                + "individual, so the two members' lists are different by design")
            .doesNotContain(runId);
    }

    @Test
    @DisplayName("The owner still reaches their own run, so the refusals above mean something")
    void theOwnerReachesTheirOwnRun() {
        String runId = startAndComplete();

        HttpResponse<String> read = get(appUrl(RUNS + "/" + runId), owner.accessToken());
        HttpResponse<String> download =
            get(appUrl(RUNS + "/" + runId + "/download"), owner.accessToken());

        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(download.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("Downloading a run that is not COMPLETE is 409, not 404")
    void downloadingAPendingRunIsAConflict() {
        UUID runId = pendingRunOf(owner);

        HttpResponse<String> response =
            get(appUrl(RUNS + "/" + runId + "/download"), owner.accessToken());

        assertThat(response.statusCode())
            .as("the run exists and is the caller's; 'no such run' would be untrue and would "
                + "make a polling client discard state it should keep. Body: %s", response.body())
            .isEqualTo(409);
        assertThat(Json.errorCode(response.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("A household mate downloading a PENDING run gets 404, not the 409")
    void ownershipIsCheckedBeforeStatus() {
        UUID runId = pendingRunOf(owner);

        HttpResponse<String> response = getWithHeader(
            appUrl(RUNS + "/" + runId + "/download"), "Authorization",
            "Bearer " + householdMate.accessToken());

        assertThat(response.statusCode())
            .as("the 409 would tell a mate that the id is real and what state it is in; the "
                + "ownership check has to come first")
            .isEqualTo(404);
    }

    @Test
    @DisplayName("DELETE removes the file as well as the row")
    void deleteRemovesTheFileToo() {
        String runId = startAndComplete();
        String key = jdbcTemplate.queryForObject(
            "SELECT file_key FROM tax_export_run WHERE id = ?", String.class,
            UUID.fromString(runId));
        assertThat(key).isNotNull();
        assertThat(storage.exists(key)).isTrue();

        HttpResponse<String> removed = delete(appUrl(RUNS + "/" + runId), null,
            "Authorization", "Bearer " + owner.accessToken());

        assertThat(removed.statusCode()).as("%s", removed.body()).isEqualTo(200);
        assertThat(storage.exists(key))
            .as("a row deleted without its file leaves bytes on the volume that nothing points "
                + "at and nothing will ever sweep — there is no retention job in this step")
            .isFalse();
        assertThat(get(appUrl(RUNS + "/" + runId), owner.accessToken()).statusCode())
            .isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM tax_export_run WHERE id = ?", Long.class,
            UUID.fromString(runId))).isZero();
    }

    @Test
    @DisplayName("An unknown run id is 404 on every route")
    void anUnknownRunIs404() {
        UUID unknown = unknownId();
        String token = "Bearer " + owner.accessToken();

        assertThat(getWithHeader(appUrl(RUNS + "/" + unknown), "Authorization", token)
            .statusCode()).isEqualTo(404);
        assertThat(getWithHeader(appUrl(RUNS + "/" + unknown + "/download"), "Authorization",
            token).statusCode()).isEqualTo(404);
        assertThat(delete(appUrl(RUNS + "/" + unknown), null, "Authorization", token)
            .statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("Every run route requires a session")
    void everyRunRouteRequiresASession() {
        UUID unknown = unknownId();

        assertThat(get(appUrl(RUNS)).statusCode()).isEqualTo(401);
        assertThat(get(appUrl(RUNS + "/" + unknown)).statusCode()).isEqualTo(401);
        assertThat(get(appUrl(RUNS + "/" + unknown + "/download")).statusCode()).isEqualTo(401);
        assertThat(delete(appUrl(RUNS + "/" + unknown), null).statusCode()).isEqualTo(401);
        assertThat(postJson(appUrl(RUNS),
            Json.object("taxYear", TAX_YEAR, "format", "CSV")).statusCode()).isEqualTo(401);
    }
}
