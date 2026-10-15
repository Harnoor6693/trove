package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.categorisation.CategoryRepository;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.common.storage.StorageException;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.taxexport.TaxLineMappingService;
import finance.trove.transactions.TransactionQueryService;
import finance.trove.users.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The generation's failure paths — the ones a member only ever sees when something has gone wrong,
 * and therefore the ones that have to be right.
 *
 * <p>The happy path is asserted end to end by {@code DataExportIT}, which downloads a real archive.
 */
class DataExportGenerationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-06T05:00:00Z");

    private final DataExportRunRepository runs = mock(DataExportRunRepository.class);
    private final DataExportCollector collector = mock(DataExportCollector.class);
    private final DataExportArchiveBuilder archives = mock(DataExportArchiveBuilder.class);
    private final TransactionQueryService transactions = mock(TransactionQueryService.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final TaxLineMappingService lines = mock(TaxLineMappingService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final LocalDiskStorage storage = mock(LocalDiskStorage.class);
    private final DataExportStatusUpdater status = mock(DataExportStatusUpdater.class);

    private final UUID runId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    private final DataExportGenerationService generation = new DataExportGenerationService(runs,
        collector, archives, transactions, categories, lines, users, storage, status,
        new DataExportProperties(null, null, null),
        new UserClock((UserTimeZoneProvider) id -> ZoneId.of("America/Toronto"),
            Clock.fixed(NOW, ZoneOffset.UTC)));

    private DataExportRun existingRun() {
        DataExportRun run = new DataExportRun(userId, householdId, NOW);
        ReflectionTestUtils.setField(run, "id", runId);
        return run;
    }

    private void aRunThatIsReadyToGenerate() {
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(existingRun()));
        when(status.transition(runId, userId, DataExportStatus.GENERATING, null)).thenReturn(true);
        when(users.findByIdAndHouseholdId(userId, householdId)).thenReturn(Optional.empty());
        when(categories.findAllVisible(householdId)).thenReturn(List.of());
        when(transactions.findAllInRange(eq(householdId), any(), any()))
            .thenReturn(List.of());
        when(collector.collect(userId, householdId)).thenReturn(Map.of());
        when(archives.build(any())).thenReturn(new byte[] {1, 2, 3});
    }

    @Test
    @DisplayName("A run that is gone before the thread picks it up is a silent no-op")
    void aDeletedRunIsNotGenerated() {
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.empty());

        generation.generateAsync(runId, userId, householdId);

        verify(status, never()).transition(any(), any(), any(), any());
        verify(storage, never()).put(any(), any(), any(byte[].class), any());
    }

    @Test
    @DisplayName("A run that cannot be moved to GENERATING is not generated either")
    void aLostTransitionStopsTheRun() {
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(existingRun()));
        when(status.transition(runId, userId, DataExportStatus.GENERATING, null))
            .thenReturn(false);

        generation.generateAsync(runId, userId, householdId);

        verify(storage, never()).put(any(), any(), any(byte[].class), any());
    }

    @Test
    @DisplayName("The archive is written into the EXPORTS area under the household id")
    void theArchiveIsFiledUnderTheHousehold() {
        aRunThatIsReadyToGenerate();
        when(storage.put(eq(StorageArea.EXPORTS), eq(householdId), any(byte[].class), eq("zip")))
            .thenReturn("exports/" + householdId + "/" + UUID.randomUUID() + ".zip");
        when(status.complete(eq(runId), eq(userId), any(), anyLong(), any())).thenReturn(true);

        generation.generateAsync(runId, userId, householdId);

        // There is no per-user segment in a storage key and one may not be added: pathOf refuses
        // anything but three segments, and that refusal is the traversal guard. Ownership lives on
        // the run row, which is what the download checks.
        verify(storage).put(StorageArea.EXPORTS, householdId, new byte[] {1, 2, 3}, "zip");

        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(status).complete(eq(runId), eq(userId), any(), eq(3L), expiry.capture());
        assertThat(expiry.getValue())
            .as("gap row 16's single 24-hour download link, stamped from the generation instant")
            .isEqualTo(NOW.plus(DataExportProperties.DEFAULT_DOWNLOAD_TTL));
    }

    @Test
    @DisplayName("If the run vanished while the archive was being written, the archive goes too")
    void anOrphanedArchiveIsDeleted() {
        aRunThatIsReadyToGenerate();
        String key = "exports/" + householdId + "/" + UUID.randomUUID() + ".zip";
        when(storage.put(eq(StorageArea.EXPORTS), eq(householdId), any(byte[].class), eq("zip")))
            .thenReturn(key);
        when(status.complete(eq(runId), eq(userId), any(), anyLong(), any())).thenReturn(false);

        generation.generateAsync(runId, userId, householdId);

        verify(storage).delete(key);
    }

    @Test
    @DisplayName("A failure records FAILED with a reason, and never a stack trace or a path")
    void aFailureIsRecordedNotSwallowed() {
        aRunThatIsReadyToGenerate();
        when(storage.put(eq(StorageArea.EXPORTS), eq(householdId), any(byte[].class), eq("zip")))
            .thenThrow(new StorageException("Could not store an object in exports"));

        generation.generateAsync(runId, userId, householdId);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(status).transition(eq(runId), eq(userId), eq(DataExportStatus.FAILED),
            reason.capture());
        assertThat(reason.getValue())
            .as("a run that sits at GENERATING for ever with nothing to explain it is the one "
                + "state a member cannot act on")
            .isNotBlank()
            .doesNotContain("\\n");
    }

    @Test
    @DisplayName("Nothing throws out of the async method, even when the failure write fails too")
    void nothingEscapesTheExecutor() {
        aRunThatIsReadyToGenerate();
        when(storage.put(eq(StorageArea.EXPORTS), eq(householdId), any(byte[].class), eq("zip")))
            .thenThrow(new StorageException("volume is full"));
        when(status.transition(runId, userId, DataExportStatus.FAILED, null))
            .thenThrow(new IllegalStateException("database is gone"));
        when(status.transition(eq(runId), eq(userId), eq(DataExportStatus.FAILED), any()))
            .thenThrow(new IllegalStateException("database is gone"));

        // The executor's uncaught-exception handler logs against no request and no run, so an
        // escape here is a failure nobody can trace back to a member.
        generation.generateAsync(runId, userId, householdId);
    }
}
