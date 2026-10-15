package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Status transitions, including the two "the run is gone" answers a caller has to act on. */
class DataExportStatusUpdaterTest {

    private static final Instant NOW = Instant.parse("2026-09-06T05:00:00Z");

    private final DataExportRunRepository runs = mock(DataExportRunRepository.class);
    private final DataExportStatusUpdater updater = new DataExportStatusUpdater(runs);

    private final UUID runId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private DataExportRun run() {
        DataExportRun run = new DataExportRun(userId, UUID.randomUUID(), NOW);
        ReflectionTestUtils.setField(run, "id", runId);
        return run;
    }

    @Test
    @DisplayName("A transition writes the status and the reason together")
    void aTransitionWritesBoth() {
        DataExportRun run = run();
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(run));

        assertThat(updater.transition(runId, userId, DataExportStatus.FAILED, "a reason"))
            .isTrue();

        assertThat(run.getStatus()).isEqualTo(DataExportStatus.FAILED);
        assertThat(run.getErrorMessage()).isEqualTo("a reason");
        verify(runs).save(run);
    }

    @Test
    @DisplayName("A reason longer than the column is truncated, not rejected and not stored whole")
    void aLongReasonIsTruncated() {
        DataExportRun run = run();
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(run));

        updater.transition(runId, userId, DataExportStatus.FAILED,
            "x".repeat(DataExportStatusUpdater.MAX_REASON_LENGTH + 50));

        assertThat(run.getErrorMessage())
            .as("a database column is not a log file, and a failed INSERT would lose the reason "
                + "altogether")
            .hasSize(DataExportStatusUpdater.MAX_REASON_LENGTH);
    }

    @Test
    @DisplayName("A blank reason leaves the previous one rather than blanking it")
    void aBlankReasonIsIgnored() {
        DataExportRun run = run();
        run.transitionTo(DataExportStatus.GENERATING, "an earlier note");
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(run));

        updater.transition(runId, userId, DataExportStatus.COMPLETE, "   ");

        assertThat(run.getErrorMessage()).isEqualTo("an earlier note");
    }

    @Test
    @DisplayName("A transition on a run that is gone answers false and writes nothing")
    void aMissingRunIsNotAnError() {
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.empty());

        assertThat(updater.transition(runId, userId, DataExportStatus.FAILED, "a reason"))
            .as("the member's account may have been deleted mid-generation, which is legitimate")
            .isFalse();
        verify(runs, never()).save(any());
    }

    @Test
    @DisplayName("complete writes the key, the size and the expiry in one go")
    void completeWritesEverythingTogether() {
        DataExportRun run = run();
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(run));
        Instant expiresAt = NOW.plusSeconds(86_400);

        assertThat(updater.complete(runId, userId, "exports/h/a.zip", 42L, expiresAt)).isTrue();

        assertThat(run.getStatus()).isEqualTo(DataExportStatus.COMPLETE);
        assertThat(run.getFileKey()).isEqualTo("exports/h/a.zip");
        assertThat(run.getFileSizeBytes()).isEqualTo(42L);
        assertThat(run.getExpiresAt())
            .as("a split between 'the archive is at this key' and 'the run is complete' is a "
                + "window in which a crash leaves a run with no file, or a file no row points at")
            .isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("complete on a run that is gone answers false so the caller deletes the archive")
    void completeOnAMissingRunAnswersFalse() {
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.empty());

        assertThat(updater.complete(runId, userId, "exports/h/a.zip", 42L, NOW)).isFalse();
    }
}
