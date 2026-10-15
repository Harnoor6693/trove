package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The second half of the 24-hour link: an expiry that only hid the archive would be theatre, so
 * this job deletes the bytes and forgets the key.
 */
class DataExportSweepTest {

    private static final Instant NOW = Instant.parse("2026-09-06T05:00:00Z");

    private final DataExportRunRepository runs = mock(DataExportRunRepository.class);
    private final LocalDiskStorage storage = mock(LocalDiskStorage.class);
    private final DataExportSweep sweep =
        new DataExportSweep(runs, storage, new DataExportProperties(null, null, null));

    private static DataExportRun lapsed(String key) {
        UUID userId = UUID.randomUUID();
        UUID householdId = UUID.randomUUID();
        DataExportRun run = new DataExportRun(userId, householdId, NOW.minusSeconds(90_000));
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        run.complete(key, 4096L, NOW.minusSeconds(60));
        return run;
    }

    @Test
    @DisplayName("A lapsed archive is deleted from the volume and its key is forgotten")
    void aLapsedArchiveIsReallyDeleted() {
        String key = "exports/" + UUID.randomUUID() + "/" + UUID.randomUUID() + ".zip";
        DataExportRun run = lapsed(key);
        when(runs.findLapsedArchives(any(), any(Pageable.class))).thenReturn(List.of(run));

        assertThat(sweep.sweep(NOW)).isEqualTo(1);

        verify(storage).delete(key);
        assertThat(run.getFileKey())
            .as("the file goes first and the key second: the other order leaves a row pointing "
                + "at bytes that are gone, which is a 500 on a download that raced the sweep")
            .isNull();
        assertThat(run.getFileSizeBytes())
            .as("a response must not advertise an archive that is not there")
            .isNull();
        assertThat(run.getStatus())
            .as("the run really did complete; rewriting history to say otherwise would tell a "
                + "member their export failed when it did not")
            .isEqualTo(DataExportStatus.COMPLETE);
        verify(runs).save(run);
    }

    @Test
    @DisplayName("A file that cannot be deleted does not stop the pass or lose the key")
    void oneBadKeyDoesNotStrandTheRest() {
        DataExportRun bad = lapsed("exports/" + UUID.randomUUID() + "/bad.zip");
        DataExportRun good = lapsed("exports/" + UUID.randomUUID() + "/good.zip");
        when(runs.findLapsedArchives(any(), any(Pageable.class)))
            .thenReturn(List.of(bad, good));
        when(storage.delete(bad.getFileKey())).thenThrow(new StorageException("unwritable"));

        assertThat(sweep.sweep(NOW))
            .as("one bad key must not keep every other member's expired archive on the volume")
            .isEqualTo(1);
        assertThat(bad.getFileKey())
            .as("the row keeps naming the file it could not delete, so the next pass tries again")
            .isNotNull();
        assertThat(good.getFileKey()).isNull();
    }

    @Test
    @DisplayName("Nothing lapsed is a no-op that touches neither the volume nor a row")
    void nothingLapsedIsANoOp() {
        when(runs.findLapsedArchives(any(), any(Pageable.class))).thenReturn(List.of());

        assertThat(sweep.sweep(NOW)).isZero();
        verify(storage, org.mockito.Mockito.never()).delete(any());
        verify(runs, org.mockito.Mockito.never()).save(any());
    }
}
