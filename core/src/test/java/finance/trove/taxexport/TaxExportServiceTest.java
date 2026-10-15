package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.taxexport.dto.CreateTaxExportRunRequest;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Clock;
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
 * The run lifecycle's decisions: which year is acceptable, whose calendar decides, where the
 * jurisdiction comes from, and what happens when the executor refuses the work.
 *
 * <p>The window edges themselves ({@link TaxExportGenerationService#windowStart} /
 * {@link TaxExportGenerationService#windowEnd}) are asserted in {@link TaxExportWindowTest}.
 */
class TaxExportServiceTest {

    /** 1 January 2026, 06:00 UTC — which is still 31 December 2025 in Vancouver. */
    private static final Instant NEW_YEAR_UTC = Instant.parse("2026-01-01T06:00:00Z");

    private final TaxExportRunRepository runs = mock(TaxExportRunRepository.class);
    private final TaxExportGenerationService generation =
        mock(TaxExportGenerationService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final LocalDiskStorage storage = mock(LocalDiskStorage.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    private static User member(UUID id, UUID householdId, Jurisdiction country, ZoneId zone) {
        User user = new User(householdId, UserRole.MEMBER, "member@trove.test", "Member",
            country, country.defaultCurrency(), zone);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private TaxExportService serviceIn(ZoneId zone, User member) {
        when(users.findByIdAndHouseholdId(userId, householdId)).thenReturn(Optional.of(member));
        when(runs.save(any(TaxExportRun.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        UserTimeZoneProvider zones = id -> zone;
        return new TaxExportService(runs, generation, users,
            new UserClock(zones, Clock.fixed(NEW_YEAR_UTC, ZoneOffset.UTC)), storage);
    }

    @Test
    @DisplayName("The jurisdiction is stamped from users.country, never from the request")
    void theJurisdictionComesFromTheMember() {
        TaxExportService service = serviceIn(ZoneId.of("America/Toronto"),
            member(userId, householdId, Jurisdiction.US, ZoneId.of("America/Toronto")));

        TaxExportRun run = service.request(userId, householdId,
            new CreateTaxExportRunRequest(2025, TaxExportFormat.CSV));

        assertThat(run.getJurisdiction())
            .as("a stored run must be reproducible from the row alone after the member changes "
                + "country, which is why the caller cannot override this")
            .isEqualTo(Jurisdiction.US);
        assertThat(run.getStatus()).isEqualTo(TaxExportStatus.PENDING);
    }

    @Test
    @DisplayName("The ceiling on taxYear is the member's own current year, not the container's")
    void theYearCeilingIsTheMembersOwnCalendar() {
        // The same instant: 2026 in Toronto, still 2025 in Vancouver.
        TaxExportService toronto = serviceIn(ZoneId.of("America/Toronto"),
            member(userId, householdId, Jurisdiction.CA, ZoneId.of("America/Toronto")));
        assertThat(toronto.request(userId, householdId,
            new CreateTaxExportRunRequest(2026, TaxExportFormat.CSV)).getTaxYear())
            .as("gap P2-15: it is already 2026 where this member is")
            .isEqualTo(2026);

        TaxExportService vancouver = serviceIn(ZoneId.of("America/Vancouver"),
            member(userId, householdId, Jurisdiction.CA, ZoneId.of("America/Vancouver")));
        assertThatThrownBy(() -> vancouver.request(userId, householdId,
            new CreateTaxExportRunRequest(2026, TaxExportFormat.CSV)))
            .as("for this member the same instant is still 2025, so 2026 is a future year")
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("A year before the earliest accepted one is 400, not a silent clamp")
    void tooEarlyAYearIsRejected() {
        TaxExportService service = serviceIn(ZoneId.of("America/Toronto"),
            member(userId, householdId, Jurisdiction.CA, ZoneId.of("America/Toronto")));

        assertThatThrownBy(() -> service.request(userId, householdId,
            new CreateTaxExportRunRequest(TaxExportService.EARLIEST_TAX_YEAR - 1,
                TaxExportFormat.PDF)))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("A rejected dispatch flips the run to FAILED before the response is sent")
    void aRejectedDispatchFailsTheRunSynchronously() {
        TaxExportService service = serviceIn(ZoneId.of("America/Toronto"),
            member(userId, householdId, Jurisdiction.CA, ZoneId.of("America/Toronto")));
        doThrow(new TaskRejectedException("queue full"))
            .when(generation).generateAsync(any(), any(), any());

        TaxExportRun run = service.request(userId, householdId,
            new CreateTaxExportRunRequest(2025, TaxExportFormat.CSV));

        assertThat(run.getStatus())
            .as("AbortPolicy is the design (§5.7): the caller always receives a real, queryable "
                + "id with an honest status rather than a run that will never move")
            .isEqualTo(TaxExportStatus.FAILED);
        assertThat(run.getErrorMessage())
            .as("the message is a fixed sentence: a rejection message names the executor and its "
                + "queue depth, which tells a member nothing and an attacker something")
            .isEqualTo("Export generation could not be queued");
    }

    @Test
    @DisplayName("A run that is not COMPLETE is a 409, not a 404")
    void anIncompleteRunIsAConflict() {
        TaxExportService service = serviceIn(ZoneId.of("America/Toronto"),
            member(userId, householdId, Jurisdiction.CA, ZoneId.of("America/Toronto")));
        UUID runId = UUID.randomUUID();
        TaxExportRun pending =
            new TaxExportRun(userId, 2025, Jurisdiction.CA, TaxExportFormat.CSV);
        ReflectionTestUtils.setField(pending, "id", runId);
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.download(userId, runId))
            .as("the run exists and belongs to the caller; telling a polling client 'no such "
                + "run' would make it discard state it should keep, and would be untrue")
            .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("The download filename is built from the run alone")
    void theFilenameComesFromTheRun() {
        TaxExportRun run = new TaxExportRun(userId, 2025, Jurisdiction.CA, TaxExportFormat.PDF);
        UUID runId = UUID.randomUUID();
        ReflectionTestUtils.setField(run, "id", runId);

        assertThat(TaxExportService.filenameFor(run))
            .as("nothing a caller supplied reaches the Content-Disposition header, so it can "
                + "carry no path separator and no quote to escape")
            .isEqualTo("trove-tax-export-2025-" + runId + ".pdf");
    }

    @Test
    @DisplayName("Deleting a run removes its file as well as its row")
    void deletingARunRemovesItsFile() {
        TaxExportService service = serviceIn(ZoneId.of("America/Toronto"),
            member(userId, householdId, Jurisdiction.CA, ZoneId.of("America/Toronto")));
        UUID runId = UUID.randomUUID();
        TaxExportRun run = new TaxExportRun(userId, 2025, Jurisdiction.CA, TaxExportFormat.CSV);
        ReflectionTestUtils.setField(run, "id", runId);
        run.complete("exports/" + householdId + "/abc.csv", 10L);
        when(runs.findByIdAndUserId(runId, userId)).thenReturn(Optional.of(run));
        when(storage.delete(anyString())).thenReturn(true);
        when(runs.deleteByIdAndUserId(runId, userId)).thenReturn(1L);

        service.delete(userId, runId);

        org.mockito.Mockito.verify(storage).delete("exports/" + householdId + "/abc.csv");
        org.mockito.Mockito.verify(runs).deleteByIdAndUserId(runId, userId);
    }

}
