package finance.trove.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.app.support.LedgerSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The {@code import_batch} table and {@code transaction.import_batch_id}
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-12; {@code 06-COLLAPSE-PLAN.md} §3 V22).
 *
 * <p><strong>Only the schema, deliberately.</strong> {@code POST /v1/imports}, its preview, its
 * commit and its undo are collapse step 7's. This step creates what those endpoints will write to,
 * because {@code transaction} (V19) needs the column and could not carry a forward foreign key to
 * a table that did not exist yet — the same ordering §3 uses for {@code transaction.receipt_id}.
 * So the assertions here are made in SQL: there is no endpoint to drive, and asserting the
 * constraints now is what stops step 7 from discovering them the hard way.
 */
class ImportBatchIT extends LedgerSupport {

    @Test
    @DisplayName("A batch links to its transactions, and deleting it nulls the link rather than "
        + "deleting a household's ledger")
    void theBatchLinkIsSetNullOnDelete() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Import " + UUID.randomUUID());
        UUID transactionId = UUID.fromString(
            createManualTransaction(admin, accountId, "9.99", "2026-09-04", "DEBIT", "Imported")
                .path("id").asString());

        UUID batchId = insertBatch(admin, accountId, "CSV", "COMMITTED");
        jdbcTemplate.update("UPDATE transaction SET import_batch_id = ? WHERE id = ?", batchId,
            transactionId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT import_batch_id FROM transaction WHERE id = ?", UUID.class, transactionId))
            .isEqualTo(batchId);

        jdbcTemplate.update("DELETE FROM import_batch WHERE id = ?", batchId);

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM transaction WHERE id = ?", Integer.class, transactionId))
            .as("deleting a batch is an administrative act about the IMPORT; it must never take a "
                + "household's transactions with it")
            .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT import_batch_id FROM transaction WHERE id = ?", UUID.class, transactionId))
            .isNull();
    }

    @Test
    @DisplayName("import_batch_id must name a real batch — V22 adds the FK V19 could not declare")
    void theForeignKeyExists() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Import " + UUID.randomUUID());
        UUID transactionId = UUID.fromString(
            createManualTransaction(admin, accountId, "9.99", "2026-09-04", "DEBIT", "Imported")
                .path("id").asString());

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE transaction SET import_batch_id = ? WHERE id = ?", UUID.randomUUID(),
            transactionId))
            .as("V19 could not declare this FK because import_batch did not exist yet, so V22 "
                + "adds it by ALTER — the same ordering §3 uses for transaction.receipt_id")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("source_kind is CSV or OFX only, and status is one of the three batch states")
    void theCheckConstraintsHold() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Import " + UUID.randomUUID());

        for (String kind : List.of("CSV", "OFX")) {
            assertThat(insertBatch(admin, accountId, kind, "PREVIEW")).isNotNull();
        }
        assertThatThrownBy(() -> insertBatch(admin, accountId, "SIMPLEFIN", "PREVIEW"))
            .as("SIMPLEFIN is fetched over the network and MANUAL is typed in; neither has a file "
                + "to batch")
            .isInstanceOf(DataIntegrityViolationException.class);

        for (String status : List.of("PREVIEW", "COMMITTED", "UNDONE")) {
            assertThat(insertBatch(admin, accountId, "CSV", status)).isNotNull();
        }
        assertThatThrownBy(() -> insertBatch(admin, accountId, "CSV", "HALF_DONE"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("A batch is scoped to a household and carries its counters and mapping")
    void aBatchCarriesItsShape() {
        Session admin = loginBootstrapAdmin();
        String accountId = createManualAccount(admin, "Import " + UUID.randomUUID());

        UUID batchId = jdbcTemplate.queryForObject(
            "INSERT INTO import_batch (household_id, account_id, source_kind, mapping_json, "
                + "row_count, new_count, dup_count, status) "
                + "VALUES (?, ?, 'CSV', ?::jsonb, 120, 118, 2, 'COMMITTED') RETURNING id",
            UUID.class, admin.householdId(), UUID.fromString(accountId),
            "{\"date\":0,\"description\":1,\"amount\":2}");

        assertThat(jdbcTemplate.queryForObject(
            "SELECT household_id FROM import_batch WHERE id = ?", UUID.class, batchId))
            .isEqualTo(admin.householdId());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT mapping_json->>'amount' FROM import_batch WHERE id = ?", String.class,
            batchId))
            .as("jsonb, not text: the mapping is checked by Postgres on write")
            .isEqualTo("2");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT row_count - new_count - dup_count FROM import_batch WHERE id = ?",
            Integer.class, batchId)).isZero();
    }

    private UUID insertBatch(Session session, String accountId, String sourceKind, String status) {
        return jdbcTemplate.queryForObject(
            "INSERT INTO import_batch (household_id, account_id, source_kind, status) "
                + "VALUES (?, ?, ?, ?) RETURNING id",
            UUID.class, session.householdId(), UUID.fromString(accountId), sourceKind, status);
    }
}
