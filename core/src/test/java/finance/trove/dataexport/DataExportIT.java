package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.transactions.TransferPairingJob;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /v1/me/export} end to end: step up, poll, download, open the archive
 * (collapse step 18; gap row 16).
 *
 * <p><strong>This class asserts the opposite of every ledger IT in the tree.</strong> Everywhere
 * else a household mate must see a household mate's rows, because the ledger is shared. Here they
 * must not: the archive is a copy of one person's whole financial life, so a mate asking for
 * another member's run gets the same 404 a stranger does — exactly as a tax filing is individual.
 * The transactions INSIDE the archive are still the household's.
 *
 * <p>Each test uses its own member, because gap row 16's cap is one run per member per day and a
 * shared member would make the second test in the class depend on the first.
 */
class DataExportIT extends LedgerSupport {

    private static final String EXPORTS = "/v1/me/export";

    /**
     * The named regression of gap row 16, driven through the real route rather than the builder.
     */
    private static final String HOSTILE_MERCHANT = "=HYPERLINK(\"http://evil\",\"click\")";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private LocalDiskStorage storage;

    @Autowired
    private DataExportSweep sweep;

    @Autowired
    private TransferPairingJob pairingJob;

    private Session admin;

    @BeforeEach
    void bootstrap() {
        admin = loginBootstrapAdmin();
    }

    // ------------------------------------------------------------------ fixtures

    private String stepUpBody() {
        return Json.object("password", MEMBER_PASSWORD);
    }

    private HttpResponse<String> start(Session member, String body) {
        return postJson(appUrl(EXPORTS), body,
            "Authorization", "Bearer " + member.accessToken());
    }

    private String startAndComplete(Session member) {
        HttpResponse<String> created = start(member, stepUpBody());
        assertThat(created.statusCode()).as("%s", created.body()).isEqualTo(202);
        String runId = Json.data(created.body()).path("id").asString();

        for (int attempt = 0; attempt < 200; attempt++) {
            JsonNode run = Json.data(
                get(appUrl(EXPORTS + "/" + runId), member.accessToken()).body());
            String status = run.path("status").asString();
            if ("COMPLETE".equals(status)) {
                assertThat(run.path("expiresAt").asString(""))
                    .as("gap row 16's single 24-hour link: a completed run has to say when it "
                        + "lapses, or a client cannot tell a member")
                    .isNotBlank();
                return runId;
            }
            assertThat(status)
                .as("run failed: %s", run.path("errorMessage").asString(""))
                .isNotEqualTo("FAILED");
            sleepBriefly();
        }
        throw new AssertionError("run " + runId + " never reached COMPLETE");
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling", e);
        }
    }

    /**
     * The archive's own bytes, from the volume the download served them off.
     *
     * <p><strong>Why the ZIP is read off the volume rather than out of the response body.</strong>
     * {@code BaseIT} decodes every response as a UTF-8 string, which destroys a ZIP. The HTTP call
     * still runs above — it proves the status, both headers, the declared content type and the
     * ownership check, and it is validated against the contract like every other request in the
     * suite — and the bytes are then read back through {@code LocalDiskStorage}, which is where
     * the download served them from anyway. {@code TaxExportIT} does the same for the same reason.
     */
    private Map<String, byte[]> archiveOf(Session member, String runId) {
        HttpResponse<String> served = getWithHeader(appUrl(EXPORTS + "/" + runId + "/download"),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(served.statusCode()).as("%s", served.body()).isEqualTo(200);

        String key = jdbcTemplate.queryForObject(
            "SELECT file_key FROM data_export_run WHERE id = ?", String.class,
            UUID.fromString(runId));
        assertThat(key)
            .as("a COMPLETE run always has a key; the status, the key and the expiry are written "
                + "together")
            .isNotNull();
        assertThat(key)
            .as("there is no per-user segment in a storage key and one may not be added — pathOf "
                + "refuses anything but three segments, and that refusal is the traversal guard")
            .startsWith("exports/" + member.householdId() + "/");
        return unzip(storage.readAllBytes(key));
    }

    private static Map<String, byte[]> unzip(byte[] archive) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(archive),
            StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("The archive holds the manifest, transactions.csv and every JSON dataset")
    void theArchiveIsBuiltAndDownloadable() {
        Session member = inviteAndRegister(admin, "data-export-owner");
        String accountId = createManualAccount(member, "Chequing");
        // The amount is unsigned on the wire — the contract's pattern is
        // ^[0-9]{1,15}(\.[0-9]{1,4})?$ and `type` carries the sign — so the CSV's escaped
        // negative is asserted in DataExportArchiveBuilderTest, where a signed value can be
        // handed to the builder directly.
        createManualTransaction(member, accountId, "12.34", todayForCaller(member), "DEBIT",
            HOSTILE_MERCHANT);

        String runId = startAndComplete(member);

        HttpResponse<String> headers = getWithHeader(
            appUrl(EXPORTS + "/" + runId + "/download"), "Authorization",
            "Bearer " + member.accessToken());
        assertThat(headers.headers().firstValue("Content-Type").orElse(""))
            .isEqualTo("application/zip");
        assertThat(headers.headers().firstValue("Content-Disposition").orElse(""))
            .as("a browser must save this rather than render it, and the name must carry no "
                + "email address")
            .startsWith("attachment; filename=\"trove-data-export-" + runId + ".zip\"");
        assertThat(headers.headers().firstValue("X-Content-Type-Options").orElse(""))
            .isEqualTo("nosniff");

        Map<String, byte[]> entries = archiveOf(member, runId);
        assertThat(entries.keySet())
            .as("gap row 16 asks for transactions.csv plus the datasets as JSON, with a manifest "
                + "and a schema version")
            .contains("manifest.json", "transactions.csv", "accounts.json", "categories.json",
                "merchant-rules.json", "budget-periods.json", "envelopes.json",
                "savings-goals.json", "subscriptions.json", "net-worth-snapshots.json",
                "assets.json", "liabilities.json", "receipts.json",
                "coach-conversations.json", "tax-line-mappings.json",
                "notification-preferences.json");

        JsonNode manifest = objectMapper.readTree(entries.get("manifest.json"));
        assertThat(manifest.path("schemaVersion").asInt()).isEqualTo(1);
        assertThat(manifest.path("userId").asString()).isEqualTo(member.userId().toString());
        assertThat(manifest.path("householdId").asString())
            .isEqualTo(member.householdId().toString());
        for (JsonNode file : manifest.path("files")) {
            String name = file.path("name").asString();
            assertThat(entries).as("the manifest lists %s", name).containsKey(name);
            assertThat(file.path("sizeBytes").asLong())
                .as("the manifest's byte count for %s must be this archive's", name)
                .isEqualTo(entries.get(name).length);
        }

        String csv = new String(entries.get("transactions.csv"), StandardCharsets.UTF_8)
            .replaceFirst("^\uFEFF", "");
        assertThat(csv.lines().findFirst().orElse(""))
            .as("the transactions file is CsvExportBuilder's, with the column list step 15 fixed")
            .isEqualTo("date,merchant,description,notes,amount,currency,category,taxLineCode,"
                + "taxLineLabel,receiptId,type,accountId,transferGroupId,pending");
        assertThat(csv)
            .as("gap row 16's named regression, proved through THIS pipeline rather than through "
                + "the builder in isolation: the merchant is neutralised, not merely escaped "
                + "somewhere else")
            .contains("\"'=HYPERLINK(")
            .doesNotContain(",=HYPERLINK(");

        assertThat(new String(entries.get("accounts.json"), StandardCharsets.UTF_8))
            .as("the archive says which institutions the household connected, never how to "
                + "connect to them")
            .doesNotContain("configEncrypted");
        assertThat(new String(entries.get("receipts.json"), StandardCharsets.UTF_8))
            .as("receipts are metadata only: no storage key ever leaves the server")
            .doesNotContain("receipts/" + member.householdId());
    }

    @Test
    @DisplayName("A full export keeps paired transfer legs, names the account and has no totals (B2-03)")
    void theFullExportKeepsTransferLegs() {
        Session member = inviteAndRegister(admin, "data-export-transfers");
        String chequing = createManualAccount(member, "Chequing " + UUID.randomUUID());
        String card = createManualAccount(member, "Card " + UUID.randomUUID());
        String today = todayForCaller(member);
        createManualTransaction(member, chequing, "250.00", today, "DEBIT", "Card payment out");
        createManualTransaction(member, card, "250.00", today, "CREDIT", "Card payment in");
        pairingJob.runOnce();

        String csv = new String(archiveOf(member, startAndComplete(member))
            .get("transactions.csv"), StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");

        assertThat(csv)
            .as("a user leaving with everything needs both legs of a transfer")
            .contains("Card payment out")
            .contains("Card payment in")
            .contains(chequing)
            .contains(card)
            .as("a rectangular file an importer can read: no totals section appended")
            .doesNotContain("Totals by tax line");
        assertThat(csv.lines().findFirst().orElse(""))
            .endsWith(",type,accountId,transferGroupId,pending");
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("A second run the same day is 409 conflict")
    void oneRunPerMemberPerDay() {
        Session member = inviteAndRegister(admin, "data-export-cap");

        assertThat(start(member, stepUpBody()).statusCode()).isEqualTo(202);
        HttpResponse<String> second = start(member, stepUpBody());

        assertThat(second.statusCode())
            .as("gap row 16's 1/day, counted on the member's own local day: %s", second.body())
            .isEqualTo(409);
        assertThat(Json.errorCode(second.body())).isEqualTo("conflict");
        assertThat(Json.parse(second.body()).path("error").path("detail").asString())
            .as("a refusal a member cannot act on is a refusal they will retry")
            .contains("already asked");
    }

    @Test
    @DisplayName("A wrong password is 403 step-up-required and starts no run")
    void theStepUpIsEnforced() {
        Session member = inviteAndRegister(admin, "data-export-stepup");

        HttpResponse<String> refused = start(member,
            Json.object("password", "not-the-right-password"));

        assertThat(refused.statusCode()).as("%s", refused.body()).isEqualTo(403);
        assertThat(Json.errorCode(refused.body())).isEqualTo("step-up-required");
        assertThat(Json.elements(Json.data(
                get(appUrl(EXPORTS), member.accessToken()).body())))
            .as("a failed step-up must not spend the member's one run a day")
            .isEmpty();
    }

    @Test
    @DisplayName("A household mate gets 404 on the run and on its download")
    void aHouseholdMateCannotReachAnotherMembersArchive() {
        Session owner = inviteAndRegister(admin, "data-export-mate-owner");
        Session mate = inviteAndRegister(admin, "data-export-mate-other");
        assertThat(mate.householdId())
            .as("these two share a ledger; the point of this test is that they do not share an "
                + "archive")
            .isEqualTo(owner.householdId());

        String runId = startAndComplete(owner);
        String bearer = "Bearer " + mate.accessToken();

        HttpResponse<String> read = getWithHeader(appUrl(EXPORTS + "/" + runId), "Authorization",
            bearer);
        HttpResponse<String> file = getWithHeader(appUrl(EXPORTS + "/" + runId + "/download"),
            "Authorization", bearer);

        assertThat(read.statusCode()).as("%s", read.body()).isEqualTo(404);
        assertThat(file.statusCode()).as("%s", file.body()).isEqualTo(404);
        assertThat(Json.errorCode(read.body()))
            .as("404 and not 403: a 403 would confirm the other member's run exists")
            .isEqualTo("not-found");
        assertThat(Json.elements(Json.data(get(appUrl(EXPORTS), mate.accessToken()).body()))
            .stream().map(row -> row.path("id").asString()).toList())
            .doesNotContain(runId);
    }

    @Test
    @DisplayName("Downloading a run that is still PENDING is 409, not 404")
    void anUnfinishedRunIsAConflict() {
        Session member = inviteAndRegister(admin, "data-export-pending");
        // Inserted directly, because the only other way to observe a PENDING run is to beat the
        // background executor to it, and a test that races a thread pool reports a scheduling
        // accident as a defect. The route, the ownership check and the status check below are all
        // production code.
        UUID runId = jdbcTemplate.queryForObject(
            "INSERT INTO data_export_run (user_id, household_id, status) "
                + "VALUES (?, ?, 'PENDING') RETURNING id",
            UUID.class, member.userId(), member.householdId());

        HttpResponse<String> response = getWithHeader(
            appUrl(EXPORTS + "/" + runId + "/download"), "Authorization",
            "Bearer " + member.accessToken());

        assertThat(response.statusCode())
            .as("the run exists and belongs to the caller; 404 would tell a polling client to "
                + "discard state it should keep: %s", response.body())
            .isEqualTo(409);
        assertThat(Json.errorCode(response.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("An expired 24-hour link answers the same 409 and names nothing on disk")
    void anExpiredLinkIsAConflict() {
        Session member = inviteAndRegister(admin, "data-export-expired");
        String runId = startAndComplete(member);
        jdbcTemplate.update("UPDATE data_export_run SET expires_at = now() - interval '1 minute' "
            + "WHERE id = ?::uuid", runId);

        HttpResponse<String> response = getWithHeader(
            appUrl(EXPORTS + "/" + runId + "/download"), "Authorization",
            "Bearer " + member.accessToken());

        assertThat(response.statusCode()).as("%s", response.body()).isEqualTo(409);
        assertThat(Json.parse(response.body()).path("error").path("detail").asString())
            .contains("expired")
            .as("a refusal must never name a path on the volume")
            .doesNotContain("exports/");
    }

    @Test
    @DisplayName("The expiry sweep deletes the archive, and the run keeps its own history")
    void theSweepDeletesTheArchive() {
        Session member = inviteAndRegister(admin, "data-export-sweep");
        String runId = startAndComplete(member);
        jdbcTemplate.update("UPDATE data_export_run SET expires_at = now() - interval '1 minute' "
            + "WHERE id = ?::uuid", runId);

        // The job's own body, driven directly: waiting an hour for its cron is not a test.
        sweep.sweep(Instant.now());

        JsonNode run = Json.data(get(appUrl(EXPORTS + "/" + runId), member.accessToken()).body());
        assertThat(run.path("fileSizeBytes").isNull())
            .as("a response must not advertise an archive that has been deleted")
            .isTrue();
        assertThat(run.path("status").asString())
            .as("the run really did complete; rewriting history would tell a member their export "
                + "failed when it did not")
            .isEqualTo("COMPLETE");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT file_key FROM data_export_run WHERE id = ?::uuid", String.class, runId))
            .as("an expiry that only hid the archive would be theatre: the bytes are a complete "
                + "copy of somebody's financial life")
            .isNull();
    }

}
