package finance.trove.dataexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.auth.StepUpVerifier;
import finance.trove.auth.dto.StepUpRequest;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The run lifecycle's decisions: whose calendar the one-per-day cap is counted on, what happens
 * when the executor refuses the work, and what a download that has nothing to send answers.
 */
class DataExportServiceTest {

    /**
     * 6 September 2026, 05:00 UTC.
     *
     * <p>Chosen because it is the SAME instant in two different local days: 06 September in
     * Toronto (UTC-4) and still 05 September in Vancouver (UTC-7). That is the whole point of the
     * cap test below — a cap counted on the container's UTC day would give both members the same
     * boundary and would be wrong for at least one of them.
     */
    private static final Instant MORNING_UTC = Instant.parse("2026-09-06T05:00:00Z");

    private final DataExportRunRepository runs = mock(DataExportRunRepository.class);
    private final DataExportGenerationService generation =
        mock(DataExportGenerationService.class);
    private final StepUpVerifier stepUp = mock(StepUpVerifier.class);
    private final LocalDiskStorage storage = mock(LocalDiskStorage.class);
    private final HouseholdRepository households = mock(HouseholdRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    /**
     * The service with a REAL {@link DataExportRunStarter} behind it.
     *
     * <p>Real rather than mocked on purpose: the cap and the INSERT are one decision taken behind
     * one lock, and a mocked starter would let this class go on asserting a cap that the shipped
     * code no longer computes. The household lookup is stubbed to succeed, because the lock's
     * absence — not its result — is what the fixed defect was.
     */
    private DataExportService serviceIn(ZoneId zone) {
        when(runs.save(any(DataExportRun.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(households.findByIdForUpdate(householdId))
            .thenReturn(Optional.of(mock(Household.class)));
        UserTimeZoneProvider zones = id -> zone;
        UserClock clock = new UserClock(zones, Clock.fixed(MORNING_UTC, ZoneOffset.UTC));
        DataExportProperties properties = new DataExportProperties(null, null, null);
        return new DataExportService(runs,
            new DataExportRunStarter(runs, households, properties, clock),
            generation, stepUp, storage, clock);
    }

    private static DataExportRun run(UUID userId, UUID householdId) {
        DataExportRun run = new DataExportRun(userId, householdId, MORNING_UTC);
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        return run;
    }

    @Test
    @DisplayName("The step-up runs before any row is written")
    void theStepUpComesFirst() {
        DataExportService service = serviceIn(ZoneId.of("America/Toronto"));
        StepUpRequest credentials = new StepUpRequest("a-password", null);
        doThrow(new finance.trove.auth.StepUpRequiredException("nope"))
            .when(stepUp).verify(userId, credentials);

        assertThatThrownBy(() -> service.request(userId, householdId, credentials))
            .as("a failed attempt must not spend the member's one run a day, and must not leave "
                + "a PENDING row behind for a background thread to pick up")
            .isInstanceOf(finance.trove.auth.StepUpRequiredException.class);

        verify(runs, org.mockito.Mockito.never()).save(any(DataExportRun.class));
        verify(generation, org.mockito.Mockito.never())
            .generateAsync(any(), any(), any());
    }

    @Test
    @DisplayName("The one-per-day cap is counted on the caller's own local day, not the server's")
    void theCapIsCountedInTheCallersTimezone() {
        // The same instant is 06 September in Toronto and 05 September in Vancouver. Each member
        // must be counted against THEIR day, which means the two services ask the repository
        // about two different starting instants — and the difference is exactly three hours.
        Instant torontoDayStart = Instant.parse("2026-09-06T04:00:00Z");
        Instant vancouverDayStart = Instant.parse("2026-09-05T07:00:00Z");

        when(runs.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any()))
            .thenReturn(0L);

        serviceIn(ZoneId.of("America/Toronto"))
            .request(userId, householdId, new StepUpRequest("p", null));
        verify(runs).countByUserIdAndCreatedAtGreaterThanEqual(userId, torontoDayStart);

        serviceIn(ZoneId.of("America/Vancouver"))
            .request(userId, householdId, new StepUpRequest("p", null));
        verify(runs).countByUserIdAndCreatedAtGreaterThanEqual(userId, vancouverDayStart);

        assertThat(Duration.between(vancouverDayStart, torontoDayStart))
            .as("if these two ever became the same instant the cap would be the container's day, "
                + "which is the defect gap row 15 is about")
            .isEqualTo(Duration.ofHours(21));
    }

    @Test
    @DisplayName("A second run the same day is 409 and the refusal says when they may ask again")
    void aSecondRunTheSameDayIsRefused() {
        when(runs.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenReturn(1L);
        DataExportService service = serviceIn(ZoneId.of("America/Toronto"));

        assertThatThrownBy(() ->
            service.request(userId, householdId, new StepUpRequest("p", null)))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already asked")
            .as("a refusal a member cannot act on is a refusal they will retry")
            .hasMessageContaining("2026-09-07T04:00:00Z");

        verify(generation, org.mockito.Mockito.never()).generateAsync(any(), any(), any());
    }

    @Test
    @DisplayName("A rejected executor flips the row to FAILED before the 202 is answered")
    void aRejectedExecutorFailsTheRunSynchronously() {
        when(runs.countByUserIdAndCreatedAtGreaterThanEqual(eq(userId), any())).thenReturn(0L);
        doThrow(new TaskRejectedException("queue full"))
            .when(generation).generateAsync(any(), eq(userId), eq(householdId));

        DataExportRun answered = serviceIn(ZoneId.of("America/Toronto"))
            .request(userId, householdId, new StepUpRequest("p", null));

        assertThat(answered.getStatus())
            .as("the caller must receive a real, queryable id with an honest status rather than "
                + "a run that will never move")
            .isEqualTo(DataExportStatus.FAILED);
        assertThat(answered.getErrorMessage())
            .as("the executor's own message names the pool and its queue depth, which tells a "
                + "member nothing and an attacker something")
            .isEqualTo("The export could not be queued")
            .doesNotContain("queue full");
    }

    @Test
    @DisplayName("Downloading a run that is still generating is 409, not 404")
    void anUnfinishedRunIsAConflict() {
        DataExportRun pending = run(userId, householdId);
        when(runs.findByIdAndUserId(pending.getId(), userId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> serviceIn(ZoneId.of("America/Toronto"))
            .download(userId, pending.getId()))
            .as("the run exists and belongs to the caller; 404 would tell a polling client to "
                + "discard state it should keep")
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("Downloading after the 24-hour link lapsed is the same 409, and says so")
    void anExpiredLinkIsAConflict() {
        DataExportRun expired = run(userId, householdId);
        expired.complete("exports/" + householdId + "/" + UUID.randomUUID() + ".zip", 42L,
            MORNING_UTC.minusSeconds(1));
        when(runs.findByIdAndUserId(expired.getId(), userId)).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> serviceIn(ZoneId.of("America/Toronto"))
            .download(userId, expired.getId()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("expired")
            .as("a refusal must never name anything on disk")
            .hasMessageNotContaining("exports/");
    }

    @Test
    @DisplayName("A completed, unexpired run streams its bytes under a name built from its id")
    void aCompletedRunDownloads() {
        DataExportRun complete = run(userId, householdId);
        String key = "exports/" + householdId + "/" + UUID.randomUUID() + ".zip";
        complete.complete(key, 3L, MORNING_UTC.plusSeconds(3600));
        when(runs.findByIdAndUserId(complete.getId(), userId)).thenReturn(Optional.of(complete));
        when(storage.readAllBytes(key)).thenReturn(new byte[] {1, 2, 3});

        DataExportService.DownloadableArchive archive =
            serviceIn(ZoneId.of("America/Toronto")).download(userId, complete.getId());

        assertThat(archive.content()).containsExactly(1, 2, 3);
        assertThat(archive.filename())
            .as("built from the run's own id and nothing a caller supplied, and carrying no "
                + "email address — this file lands in a downloads folder")
            .isEqualTo("trove-data-export-" + complete.getId() + ".zip");
    }

    @Test
    @DisplayName("A run that is not the caller's is 404, a household mate's included")
    void somebodyElsesRunIsNotFound() {
        UUID otherRun = UUID.randomUUID();
        when(runs.findByIdAndUserId(otherRun, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
            serviceIn(ZoneId.of("America/Toronto")).get(userId, otherRun))
            .as("404 rather than 403: an archive is one person's whole financial life, and a 403 "
                + "would confirm another member's run id is real")
            .isInstanceOf(finance.trove.common.exception.ResourceNotFoundException.class);
    }
}
