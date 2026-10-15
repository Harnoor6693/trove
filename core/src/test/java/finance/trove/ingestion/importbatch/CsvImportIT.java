package finance.trove.ingestion.importbatch;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.ImportSupport;
import finance.trove.app.support.Json;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A CSV statement, previewed and then committed
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-12).
 *
 * <p>Two things here are impossible to prove anywhere else: that a preview writes <strong>no
 * transaction row at all</strong> against a real database, and that re-importing the identical
 * file inserts nothing — which is what the deterministic sha256 identifier buys and what the fuzzy
 * pass could never do for a CSV, because a CSV row arrives already posted and the pre-collapse
 * fuzzy query required a pending candidate (§6 M0, defect 2).
 */
class CsvImportIT extends ImportSupport {

    private static final String STATEMENT = """
        Date,Description,Amount
        2026-09-01,GROCERY STORE,-42.50
        2026-09-02,PAYROLL,1500.00
        2026-09-03,COFFEE SHOP,-4.25
        """;

    private Session admin;
    private String accountId;

    @BeforeEach
    void setUpAnAccount() {
        admin = loginBootstrapAdmin();
        accountId = createAccount(admin, CSV, "CSV account " + UUID.randomUUID(), "CHECKING",
            "CAD");
    }

    @Test
    @DisplayName("A preview classifies every row and writes nothing")
    void aPreviewWritesNothing() {
        JsonNode preview = uploadCsv(admin, STATEMENT, accountId);

        assertThat(preview.path("status").asString()).isEqualTo("PREVIEW");
        assertThat(preview.path("sourceKind").asString()).isEqualTo("CSV");
        assertThat(preview.path("accountId").asString()).isEqualTo(accountId);
        assertThat(preview.path("rowCount").asInt()).isEqualTo(3);
        assertThat(preview.path("newCount").asInt()).isEqualTo(3);
        assertThat(preview.path("duplicateCount").asInt()).isZero();
        assertThat(preview.path("invalidCount").asInt()).isZero();
        assertThat(preview.path("originalFilename").asString()).isEqualTo("statement.csv");
        assertThat(preview.path("mapping").path("dateColumn").asString()).isEqualTo("Date");
        assertThat(preview.path("mapping").path("amountColumn").asString()).isEqualTo("Amount");
        assertThat(Json.elements(preview.path("rows"))).hasSize(3);
        assertThat(Json.elements(preview.path("rows")).get(0).path("verdict").asString())
            .isEqualTo("NEW");

        assertThat(transactionIds(admin, "accountId=" + accountId))
            .as("DeduplicationService.reconcileOne INSERTS; the preview goes through the "
                + "read-only classify() instead, and this is what proves it")
            .isEmpty();
    }

    @Test
    @DisplayName("The preview can be re-read, and still writes nothing")
    void thePreviewCanBeReRead() {
        String batchId = uploadCsv(admin, STATEMENT, accountId).path("id").asString();

        JsonNode again = previewOf(admin, batchId);

        assertThat(again.path("id").asString()).isEqualTo(batchId);
        assertThat(again.path("newCount").asInt()).isEqualTo(3);
        assertThat(transactionIds(admin, "accountId=" + accountId)).isEmpty();
    }

    @Test
    @DisplayName("A commit writes the rows through the sync pipeline and stamps the batch on them")
    void aCommitWritesTheRows() {
        String batchId = uploadCsv(admin, STATEMENT, accountId).path("id").asString();

        JsonNode log = commit(admin, batchId, null);

        assertThat(log.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(log.path("transactionsFetched").asInt()).isEqualTo(3);
        assertThat(log.path("transactionsNew").asInt()).isEqualTo(3);
        assertThat(log.path("sourceKind").asString()).isEqualTo("CSV");
        assertThat(transactionIds(admin, "accountId=" + accountId)).hasSize(3);
        assertThat(listTransactions(admin, "accountId=" + accountId))
            .allSatisfy(row -> assertThat(row.path("importBatchId").asString())
                .as("every inserted row carries the batch that produced it — without it an undo "
                    + "would have nothing to undo")
                .isEqualTo(batchId));

        assertThat(previewOf(admin, batchId).path("status").asString()).isEqualTo("COMMITTED");
    }

    @Test
    @DisplayName("Re-importing the identical file inserts nothing — the sha256 id is the key")
    void aReImportOfTheSameFileIsIdempotent() {
        commit(admin, uploadCsv(admin, STATEMENT, accountId).path("id").asString(), null);

        JsonNode secondPreview = uploadCsv(admin, STATEMENT, accountId);
        assertThat(secondPreview.path("newCount").asInt()).isZero();
        assertThat(secondPreview.path("duplicateCount").asInt())
            .as("matched on the row's own identifier, not on the ±2 day ±$0.50 window")
            .isEqualTo(3);
        assertThat(secondPreview.path("fuzzyDuplicateCount").asInt()).isZero();

        JsonNode log = commit(admin, secondPreview.path("id").asString(), null);

        assertThat(log.path("transactionsNew").asInt()).isZero();
        assertThat(log.path("transactionsSkipped").asInt()).isEqualTo(3);
        assertThat(transactionIds(admin, "accountId=" + accountId)).hasSize(3);
    }

    @Test
    @DisplayName("Committing twice is 409 — the second attempt is a mistake, not a no-op")
    void aSecondCommitIsRefused() {
        String batchId = uploadCsv(admin, STATEMENT, accountId).path("id").asString();
        commit(admin, batchId, null);

        var response = postJson(appUrl("/v1/imports/" + batchId + "/commit"), "",
            "Authorization", "Bearer " + admin.accessToken());

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(response.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("An ambiguous date is offered as candidates and confirmed on the commit")
    void anAmbiguousDateIsConfirmedNotGuessed() {
        String ambiguous = """
            Date,Description,Amount
            03/09/2026,SHOP A,-1.00
            11/09/2026,SHOP B,-2.00
            """;

        JsonNode preview = uploadCsv(admin, ambiguous, accountId);
        assertThat(preview.path("mapping").path("dateFormat").isNull()).isTrue();
        assertThat(Json.elements(preview.path("dateFormatCandidates"))).hasSize(2);
        assertThat(Json.elements(preview.path("dateFormatCandidates")).get(0).path("format")
            .asString()).isEqualTo("dd/MM/uuuu");

        String batchId = preview.path("id").asString();
        var refused = postJson(appUrl("/v1/imports/" + batchId + "/commit"), "",
            "Authorization", "Bearer " + admin.accessToken());
        assertThat(refused.statusCode())
            .as("the server will not choose between the two readings")
            .isEqualTo(400);

        JsonNode log = commit(admin, batchId,
            Json.object("mapping", java.util.Map.of("dateFormat", "MM/dd/uuuu")));

        assertThat(log.path("transactionsNew").asInt()).isEqualTo(2);
        assertThat(listTransactions(admin, "accountId=" + accountId))
            .extracting(row -> row.path("date").asString())
            .containsExactlyInAnyOrder("2026-03-09", "2026-11-09");
    }

    @Test
    @DisplayName("A CSV upload with no accountId is refused — the file does not say")
    void aCsvWithoutAnAccountIsRefused() {
        var response = upload(admin, STATEMENT.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "statement.csv", "text/csv", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("A CSV pointed at a MANUAL account is refused at upload, not stranded at commit")
    void aCsvAgainstAnAccountOfAnotherKindIsRefused() {
        String manual = createManualAccount(admin, "Manual account " + UUID.randomUUID());

        var response = upload(admin, STATEMENT.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "statement.csv", "text/csv", manual);

        assertThat(response.statusCode())
            .as("SyncOrchestrator resolves the source from the ACCOUNT's kind, so a batch created "
                + "here could never be committed — and undo refuses a batch that was never "
                + "committed, so it could never be cleaned up either")
            .isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(response.body()).contains("MANUAL");
        assertThat(transactionIds(admin, "accountId=" + manual)).isEmpty();
    }

    @Test
    @DisplayName("A CSV pointed at a SIMPLEFIN account is refused the same way")
    void aCsvAgainstASimplefinAccountIsRefused() {
        String syncable = createSyncableAccount(admin, "Syncable " + UUID.randomUUID());

        var response = upload(admin, STATEMENT.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            "statement.csv", "text/csv", syncable);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("An unreadable row is counted and reported rather than silently dropped")
    void anUnreadableRowIsReported() {
        JsonNode preview = uploadCsv(admin, """
            Date,Description,Amount
            2026-09-01,GOOD,-1.00
            2026-09-02,BAD,not-a-number
            """, accountId);

        assertThat(preview.path("rowCount").asInt()).isEqualTo(2);
        assertThat(preview.path("newCount").asInt()).isEqualTo(1);
        assertThat(preview.path("invalidCount").asInt()).isEqualTo(1);
        JsonNode bad = Json.elements(preview.path("rows")).get(1);
        assertThat(bad.path("verdict").asString()).isEqualTo("INVALID");
        assertThat(bad.path("reason").asString()).contains("amount");

        JsonNode log = commit(admin, preview.path("id").asString(), null);
        assertThat(log.path("status").asString()).isEqualTo("PARTIAL_FAILURE");
        assertThat(log.path("transactionsFailed").asInt()).isEqualTo(1);
        assertThat(log.path("transactionsNew").asInt()).isEqualTo(1);
    }
}
