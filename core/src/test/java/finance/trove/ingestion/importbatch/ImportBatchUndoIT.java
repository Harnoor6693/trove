package finance.trove.ingestion.importbatch;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.ImportSupport;
import finance.trove.app.support.Json;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code DELETE /v1/imports/{id}} ({@code docs/recovery/07-READINESS-GAPS.md} P2-12, gap row 12).
 *
 * <p>V22 chose {@code ON DELETE SET NULL} on {@code transaction.import_batch_id} precisely so that
 * removing a batch can never take a household's ledger with it. An undo makes the same
 * distinction, one row at a time: it removes what this batch inserted and nobody has touched, and
 * it leaves everything else exactly where it is — an edited row, and every row of every other
 * batch.
 */
class ImportBatchUndoIT extends ImportSupport {

    private static final String FIRST_FILE = """
        Date,Description,Amount
        2026-08-01,UNDO ROW ONE,-11.00
        2026-08-02,UNDO ROW TWO,-12.00
        2026-08-03,UNDO ROW THREE,-13.00
        """;

    private static final String SECOND_FILE = """
        Date,Description,Amount
        2026-08-10,OTHER BATCH ROW,-99.00
        """;

    private Session admin;
    private String accountId;

    @BeforeEach
    void setUpAnAccount() {
        admin = loginBootstrapAdmin();
        accountId = createAccount(admin, CSV, "Undo account " + UUID.randomUUID(), "CHECKING",
            "CAD");
    }

    @Test
    @DisplayName("Undo removes the untouched rows, keeps an edited one, and marks the batch UNDONE")
    void undoRemovesOnlyWhatNobodyHasTouched() {
        String batchId = commitFile(FIRST_FILE);
        String otherBatchId = commitFile(SECOND_FILE);
        List<JsonNode> rows = rowsOf(batchId);
        assertThat(rows).hasSize(3);

        String editedId = rows.get(0).path("id").asString();
        assertThat(patchJson(appUrl("/v1/transactions/" + editedId),
            Json.object("notes", "I checked this one"),
            "Authorization", "Bearer " + admin.accessToken()).statusCode()).isEqualTo(200);

        assertThat(undo(admin, batchId).statusCode()).isEqualTo(200);

        assertThat(rowsOf(batchId))
            .as("a row a member has edited stays where it is, still carrying the batch id as the "
                + "record of where it came from")
            .extracting(row -> row.path("id").asString())
            .containsExactly(editedId);
        assertThat(rowsOf(otherBatchId))
            .as("an undo never touches another batch's rows")
            .hasSize(1);
        assertThat(previewOf(admin, batchId).path("status").asString()).isEqualTo("UNDONE");
    }

    @Test
    @DisplayName("A second undo is 409, and a batch that was never committed cannot be undone")
    void undoIsRefusedWhenThereIsNothingToUndo() {
        String batchId = commitFile(FIRST_FILE);
        assertThat(undo(admin, batchId).statusCode()).isEqualTo(200);

        var again = undo(admin, batchId);
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(again.body())).isEqualTo("conflict");

        String uncommitted = uploadCsv(admin, SECOND_FILE, accountId).path("id").asString();
        var neverCommitted = undo(admin, uncommitted);
        assertThat(neverCommitted.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(neverCommitted.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("An undone batch's stored upload is deleted — it can never be undone again")
    void theStoredUploadIsDeletedOnUndo() {
        String batchId = commitFile(FIRST_FILE);
        String storageKey = jdbcTemplate.queryForObject(
            "SELECT mapping_json ->> 'storageKey' FROM import_batch WHERE id = ?::uuid",
            String.class, batchId);
        assertThat(storageKey).startsWith("uploads/");

        assertThat(undo(admin, batchId).statusCode()).isEqualTo(200);

        JsonNode preview = previewOf(admin, batchId);
        assertThat(preview.path("status").asString()).isEqualTo("UNDONE");
        assertThat(Json.elements(preview.path("rows")))
            .as("StorageArea.UPLOADS keeps a file only while its batch can still be undone, so "
                + "the preview answers from the counts the commit recorded")
            .isEmpty();
        assertThat(preview.path("newCount").asInt()).isEqualTo(3);
        assertThat(Json.elements(preview.path("warnings"))).isNotEmpty();
    }

    @Test
    @DisplayName("Undoing leaves a hand-entered transaction on the same account alone")
    void undoNeverTouchesAManualRow() {
        String manualAccount = createManualAccount(admin, "Manual " + UUID.randomUUID());
        String manualId = createManualTransaction(admin, manualAccount, "5.00", "2026-08-05",
            "DEBIT", "Typed by hand").path("id").asString();
        String batchId = commitFile(FIRST_FILE);

        assertThat(undo(admin, batchId).statusCode()).isEqualTo(200);

        assertThat(getTransaction(admin, manualId).path("id").asString()).isEqualTo(manualId);
        assertThat(transactionIds(admin, "accountId=" + accountId)).isEmpty();
    }

    /** Uploads and commits one file, returning the batch id. */
    private String commitFile(String content) {
        String batchId = uploadCsv(admin, content, accountId).path("id").asString();
        commit(admin, batchId, null);
        return batchId;
    }

    /** This batch's rows, as the ledger reports them. */
    private List<JsonNode> rowsOf(String batchId) {
        return listTransactions(admin, "accountId=" + accountId).stream()
            .filter(row -> batchId.equals(row.path("importBatchId").asString()))
            .toList();
    }
}
