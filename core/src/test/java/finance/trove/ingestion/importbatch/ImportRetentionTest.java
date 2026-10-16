package finance.trove.ingestion.importbatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.accounts.AccountService;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.common.time.UserClock;
import finance.trove.ingestion.CsvColumnMapping;
import finance.trove.ingestion.ImportMapping;
import finance.trove.ingestion.SourceRegistry;
import finance.trove.ingestion.SyncOrchestrator;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The two bounds on how much of the data volume file imports may occupy
 * (confirmed review finding 3).
 *
 * <p>Before them, {@code storage.delete} was reachable from {@code ImportService.undo} alone,
 * which requires a COMMITTED batch: a batch abandoned in PREVIEW kept its upload for ever, and
 * nothing anywhere swept {@code StorageArea.UPLOADS}. Any authenticated member could therefore
 * grow the shared volume without limit, ten megabytes at a time, on the same volume the readiness
 * probe watches for free space.
 *
 * <p>Mocked collaborators here rather than a container: what is under test is which batches are
 * swept and which are refused, not the SQL — the queries themselves run against Postgres in the
 * import ITs.
 */
class ImportRetentionTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    private ImportBatchRepository batches;
    private LocalDiskStorage storage;
    private ImportFileValidator validator;
    private ImportService service;

    @BeforeEach
    void setUp() {
        batches = mock(ImportBatchRepository.class);
        storage = mock(LocalDiskStorage.class);
        validator = mock(ImportFileValidator.class);
        service = new ImportService(batches, validator, mock(SourceRegistry.class),
            mock(AccountService.class), mock(ImportPreviewBuilder.class),
            mock(SyncOrchestrator.class), mock(ImportUndoService.class), storage,
            ImportLimits.defaults(), JsonMapper.builder().build(), mock(UserClock.class));
    }

    @Test
    @DisplayName("an abandoned PREVIEW batch loses its stored file and its row")
    void anAbandonedPreviewIsSwept() {
        Instant cutoff = Instant.parse("2026-09-05T00:00:00Z");
        ImportBatch abandoned = batch("uploads/h/abandoned.csv");
        when(batches.findHouseholdIdsWithPreviewsBefore(cutoff)).thenReturn(List.of(HOUSEHOLD));
        when(batches.findAllByHouseholdIdAndStatusAndCreatedAtBefore(HOUSEHOLD,
            ImportBatchStatus.PREVIEW, cutoff)).thenReturn(List.of(abandoned));
        when(batches.deletePreview(any(), eq(HOUSEHOLD))).thenReturn(1);

        assertThat(service.purgeAbandonedPreviews(cutoff)).isEqualTo(1);

        verify(storage).delete("uploads/h/abandoned.csv");
        verify(batches).deletePreview(abandoned.getId(), HOUSEHOLD);
    }

    @Test
    @DisplayName("only PREVIEW batches are swept — a committed import is the record of what ran")
    void onlyPreviewBatchesAreSwept() {
        Instant cutoff = Instant.parse("2026-09-05T00:00:00Z");
        when(batches.findHouseholdIdsWithPreviewsBefore(cutoff)).thenReturn(List.of(HOUSEHOLD));
        when(batches.findAllByHouseholdIdAndStatusAndCreatedAtBefore(any(), any(), any()))
            .thenReturn(List.of());

        assertThat(service.purgeAbandonedPreviews(cutoff)).isZero();

        verify(batches).findAllByHouseholdIdAndStatusAndCreatedAtBefore(HOUSEHOLD,
            ImportBatchStatus.PREVIEW, cutoff);
        verify(storage, never()).delete(any());
    }

    @Test
    @DisplayName("B1-16: a preview committed after the sweep read it keeps its row and its file")
    void aBatchCommittedMidSweepIsKept() {
        Instant cutoff = Instant.parse("2026-09-05T00:00:00Z");
        ImportBatch committing = batch("uploads/h/committing.csv");
        when(batches.findHouseholdIdsWithPreviewsBefore(cutoff)).thenReturn(List.of(HOUSEHOLD));
        when(batches.findAllByHouseholdIdAndStatusAndCreatedAtBefore(HOUSEHOLD,
            ImportBatchStatus.PREVIEW, cutoff)).thenReturn(List.of(committing));
        when(batches.deletePreview(any(), eq(HOUSEHOLD))).thenReturn(0);

        assertThat(service.purgeAbandonedPreviews(cutoff)).isZero();

        verify(storage, never()).delete(any());
    }

    @Test
    @DisplayName("a batch whose mapping lost its storage key still has its row removed")
    void aBatchWithNoStoredFileIsStillRemoved() {
        Instant cutoff = Instant.parse("2026-09-05T00:00:00Z");
        ImportBatch keyless = new ImportBatch(HOUSEHOLD, UUID.randomUUID(), SourceKind.CSV, null,
            0, Instant.parse("2026-09-01T00:00:00Z"));
        when(batches.findHouseholdIdsWithPreviewsBefore(cutoff)).thenReturn(List.of(HOUSEHOLD));
        when(batches.findAllByHouseholdIdAndStatusAndCreatedAtBefore(HOUSEHOLD,
            ImportBatchStatus.PREVIEW, cutoff)).thenReturn(List.of(keyless));
        when(batches.deletePreview(any(), eq(HOUSEHOLD))).thenReturn(1);

        assertThat(service.purgeAbandonedPreviews(cutoff)).isEqualTo(1);

        verify(storage, never()).delete(any());
    }

    @Test
    @DisplayName("an upload that would take the household past its allowance is 413, not stored")
    void theHouseholdQuotaRefusesTheUploadBeforeItIsStored() {
        when(storage.usage(StorageArea.UPLOADS, HOUSEHOLD))
            .thenReturn(ImportLimits.DEFAULT_HOUSEHOLD_UPLOAD_QUOTA_BYTES - 10);

        assertThatThrownBy(() -> service.create(HOUSEHOLD, USER,
            "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8), "text/csv",
            "statement.csv", 11, UUID.randomUUID(), SourceKind.CSV))
            .isInstanceOf(UploadTooLargeException.class)
            .hasMessageContaining("already holds")
            .as("no filename, no key, no path in the refusal")
            .hasMessageNotContaining("statement.csv");

        verify(storage, never()).put(any(), any(), any(byte[].class), any());
        verify(validator, never()).detectFormat(any(), any());
    }

    @Test
    @DisplayName("an upload that fits is not refused by the quota")
    void anUploadThatFitsIsNotRefused() {
        when(storage.usage(StorageArea.UPLOADS, HOUSEHOLD)).thenReturn(10L);
        when(validator.detectFormat(any(), any())).thenThrow(new IllegalStateException("reached"));

        assertThatThrownBy(() -> service.create(HOUSEHOLD, USER,
            "Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8), "text/csv",
            "statement.csv", 11, UUID.randomUUID(), SourceKind.CSV))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("reached");
    }

    @Test
    @DisplayName("the defaults are finite: a quota and a retention window, both positive")
    void theDefaultsAreFinite() {
        ImportLimits defaults = ImportLimits.defaults();

        assertThat(defaults.householdUploadQuotaBytes()).isPositive();
        assertThat(defaults.previewRetention()).isPositive();
        assertThat(new ImportLimits(0, 0, 0, null, 0, 0, 0, Duration.ZERO))
            .as("a misconfigured install falls back to the defaults rather than to no bound")
            .isEqualTo(defaults);
        assertThat(new ImportLimits(1, 1, 1, Duration.ofSeconds(1), 1, 1, -1,
            Duration.ofSeconds(-1)).householdUploadQuotaBytes())
            .isEqualTo(ImportLimits.DEFAULT_HOUSEHOLD_UPLOAD_QUOTA_BYTES);
    }

    private static ImportBatch batch(String storageKey) {
        ImportMapping mapping = new ImportMapping(storageKey, "statement.csv", SourceKind.CSV,
            new CsvColumnMapping("Date", "Description", "Amount", null, null, null, null,
                "uuuu-MM-dd", ",", "UTF-8", true));
        return new ImportBatch(HOUSEHOLD, UUID.randomUUID(), SourceKind.CSV, mapping, 3,
            Instant.parse("2026-09-01T00:00:00Z"));
    }
}
