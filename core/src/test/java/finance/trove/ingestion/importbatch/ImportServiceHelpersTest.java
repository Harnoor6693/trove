package finance.trove.ingestion.importbatch;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.accounts.AccountType;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.SourceKind;
import finance.trove.ingestion.CsvColumnMapping;
import finance.trove.ingestion.ImportMapping;
import finance.trove.ingestion.dedup.DeduplicationResult;
import finance.trove.ingestion.importbatch.dto.ImportRowVerdict;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pieces of {@code finance.trove.ingestion.importbatch} that are pure functions of their
 * input: the filename an upload is displayed under, the batch row's own state machine, and the two
 * decisions {@code ImportPreviewBuilder} makes about a row it is about to show.
 */
class ImportServiceHelpersTest {

    @Test
    @DisplayName("the browser's filename is display text and is scrubbed before it is stored")
    void theFilenameIsScrubbed() {
        assertThat(ImportService.displayFilename("statement.csv")).isEqualTo("statement.csv");
        assertThat(ImportService.displayFilename("  spaced.csv  ")).isEqualTo("spaced.csv");
        assertThat(ImportService.displayFilename("../../etc/passwd"))
            .as("it never reaches a path — LocalDiskStorage mints its own keys — but it IS echoed "
                + "into a response a client renders")
            .isEqualTo("....etcpasswd");
        assertThat(ImportService.displayFilename("a\\b\\c.ofx")).isEqualTo("abc.ofx");
        assertThat(ImportService.displayFilename("badname.csv")).isEqualTo("badname.csv");
        assertThat(ImportService.displayFilename("delname.csv")).isEqualTo("delname.csv");
        assertThat(ImportService.displayFilename(null)).isNull();
        assertThat(ImportService.displayFilename("")).isNull();
        assertThat(ImportService.displayFilename("   ")).isNull();
        assertThat(ImportService.displayFilename("////////")).isNull();
        assertThat(ImportService.displayFilename("x".repeat(400))).hasSize(255);
    }

    @Test
    @DisplayName("a batch is PREVIEW until a commit records what it did, and UNDONE after an undo")
    void theBatchStateMachine() {
        ImportMapping mapping = new ImportMapping("uploads/h/f.csv", "f.csv", SourceKind.CSV,
            new CsvColumnMapping("Date", null, "Amount", null, null, null, null, "uuuu-MM-dd", ",",
                "UTF-8", true));
        ImportBatch batch = new ImportBatch(UUID.randomUUID(), UUID.randomUUID(), SourceKind.CSV,
            mapping, 3, Instant.parse("2026-09-05T03:00:00Z"));

        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.PREVIEW);
        assertThat(batch.getRowCount()).isEqualTo(3);
        assertThat(batch.getNewCount()).isZero();
        assertThat(batch.getDupCount()).isZero();
        assertThat(batch.getCreatedAt()).isEqualTo(Instant.parse("2026-09-05T03:00:00Z"));
        assertThat(batch.getMappingJson().storageKey()).isEqualTo("uploads/h/f.csv");
        assertThat(batch.getSourceKind()).isEqualTo(SourceKind.CSV);
        assertThat(batch.toString())
            .as("a batch's mapping carries a storage key and a member's own filename")
            .doesNotContain("uploads/h/f.csv")
            .doesNotContain("f.csv");

        batch.markCommitted(3, 2, 1);
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.COMMITTED);
        assertThat(batch.getNewCount()).isEqualTo(2);
        assertThat(batch.getDupCount()).isEqualTo(1);

        batch.markUndone();
        assertThat(batch.getStatus()).isEqualTo(ImportBatchStatus.UNDONE);
        assertThat(batch.getNewCount())
            .as("the counts say what the COMMIT did; the status says what happened afterwards")
            .isEqualTo(2);

        batch.setMappingJson(mapping.withCsv(null));
        assertThat(batch.getMappingJson().csv()).isNull();
        assertThat(batch.getMappingJson().format()).isEqualTo(SourceKind.CSV);
    }

    @Test
    @DisplayName("a preview row's currency is the file's where it is supported, the account's "
        + "otherwise — including for a code this install does not know")
    void aRowsCurrencyIsNeverGuessedAndNeverNull() {
        ConnectedAccount account = new ConnectedAccount(UUID.randomUUID(), UUID.randomUUID(),
            SourceKind.CSV, "CSV account", null, AccountType.CHECKING, CurrencyCode.USD,
            Instant.parse("2026-09-05T03:00:00Z"));

        assertThat(ImportPreviewBuilder.currencyOf("CAD", account)).isEqualTo(CurrencyCode.CAD);
        assertThat(ImportPreviewBuilder.currencyOf(" cad ", account)).isEqualTo(CurrencyCode.CAD);
        assertThat(ImportPreviewBuilder.currencyOf(null, account)).isEqualTo(CurrencyCode.USD);
        assertThat(ImportPreviewBuilder.currencyOf("   ", account)).isEqualTo(CurrencyCode.USD);
        assertThat(ImportPreviewBuilder.currencyOf("GBP", account))
            .as("a blank cell and an unsupported code mean the same thing to this server — "
                + "'the account's' — and rendering one as null and the other as a code made the "
                + "two paths disagree on the wire")
            .isEqualTo(CurrencyCode.USD);
    }

    @Test
    @DisplayName("a duplicate verdict says whether the match was certain")
    void aDuplicateVerdictSaysHowItWasMatched() {
        assertThat(ImportPreviewBuilder.verdictOf(DeduplicationResult.NEW, true))
            .isEqualTo(ImportRowVerdict.NEW);
        assertThat(ImportPreviewBuilder.verdictOf(DeduplicationResult.SKIPPED, true))
            .isEqualTo(ImportRowVerdict.DUPLICATE);
        assertThat(ImportPreviewBuilder.verdictOf(DeduplicationResult.UPDATED, false))
            .as("no identifier of its own means the ±2 day ±$0.50 window matched it, which is a "
                + "weaker claim and is reported as one")
            .isEqualTo(ImportRowVerdict.FUZZY_DUPLICATE);
    }
}
