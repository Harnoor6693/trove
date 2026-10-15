package finance.trove.users.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageException;
import finance.trove.dataexport.DataExportRun;
import finance.trove.dataexport.DataExportRunRepository;
import finance.trove.taxexport.TaxExportFormat;
import finance.trove.taxexport.TaxExportRun;
import finance.trove.taxexport.TaxExportRunRepository;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The part of the erasure no cascade performs: the files.
 *
 * <p>{@code AccountPurgeIT} drives a real pass against a real database and a real volume. This
 * class covers what an integration test cannot arrange cheaply — a file that refuses to be
 * deleted, and a member with both kinds of export run.
 */
class AccountPurgeJobTest {

    private static final Instant NOW = Instant.parse("2026-09-06T05:00:00Z");

    private final UserRepository users = mock(UserRepository.class);
    private final DataExportRunRepository dataExports = mock(DataExportRunRepository.class);
    private final TaxExportRunRepository taxExports = mock(TaxExportRunRepository.class);
    private final LocalDiskStorage storage = mock(LocalDiskStorage.class);

    private final AccountPurgeJob job = new AccountPurgeJob(users, dataExports, taxExports,
        storage, new AccountDeletionProperties(null));

    private final UUID userId = UUID.randomUUID();
    private final UUID householdId = UUID.randomUUID();

    private User deletedMember() {
        User user = new User(householdId, UserRole.MEMBER, "gone@trove.test", "Gone",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneOffset.UTC);
        ReflectionTestUtils.setField(user, "id", userId);
        user.softDelete(userId, NOW.minus(Duration.ofDays(8)));
        return user;
    }

    private DataExportRun dataRun(String key) {
        DataExportRun run = new DataExportRun(userId, householdId, NOW);
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        if (key != null) {
            // Set through reflection rather than through complete(): the mutator is
            // package-private on purpose, so only DataExportStatusUpdater can move a run, and a
            // test in another package must not be the reason that stops being true.
            ReflectionTestUtils.setField(run, "fileKey", key);
            ReflectionTestUtils.setField(run, "fileSizeBytes", 10L);
        }
        return run;
    }

    private TaxExportRun taxRun(String key) {
        TaxExportRun run = new TaxExportRun(userId, 2025, Jurisdiction.CA, TaxExportFormat.CSV);
        ReflectionTestUtils.setField(run, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(run, "fileKey", key);
        return run;
    }

    private void oneExpiredMember() {
        when(users.findPurgeCandidates(any(), any(Pageable.class)))
            .thenReturn(List.of(deletedMember()));
        when(users.eraseAccountPastItsGrace(any(), any(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("Both kinds of export archive are deleted from the volume before the row goes")
    void bothKindsOfArchiveAreDeleted() {
        oneExpiredMember();
        when(dataExports.findAllByUserId(userId)).thenReturn(
            List.of(dataRun("exports/" + householdId + "/data.zip"), dataRun(null)));
        when(taxExports.findAllByUserId(userId)).thenReturn(
            List.of(taxRun("exports/" + householdId + "/tax.csv")));

        assertThat(job.purge(NOW)).isEqualTo(1);

        verify(storage).delete("exports/" + householdId + "/data.zip");
        verify(storage).delete("exports/" + householdId + "/tax.csv");
        verify(users).eraseAccountPastItsGrace(userId, householdId,
            NOW.minus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("An account restored during the pass is not erased and keeps its archives")
    void aRestoreDuringThePassWins() {
        // The SELECT that chose this batch and the DELETE that erases this row are seconds apart
        // — the job touches the volume for every account in between — and an administrator
        // restoring somebody at the edge of the grace lands in exactly that gap. The DELETE
        // re-states the condition, so a row a restore has just un-stamped matches nothing.
        when(users.findPurgeCandidates(any(), any(Pageable.class)))
            .thenReturn(List.of(deletedMember()));
        when(users.eraseAccountPastItsGrace(any(), any(), any())).thenReturn(0);
        String key = "exports/" + householdId + "/data.zip";
        when(dataExports.findAllByUserId(userId)).thenReturn(List.of(dataRun(key)));
        when(taxExports.findAllByUserId(userId)).thenReturn(List.of());

        assertThat(job.purge(NOW))
            .as("an account an administrator has just brought back must not be counted as erased")
            .isZero();

        verify(storage, never())
            .delete(any());
    }

    @Test
    @DisplayName("A run with no archive is skipped rather than resolved to a null key")
    void aRunWithNoFileIsSkipped() {
        oneExpiredMember();
        when(dataExports.findAllByUserId(userId)).thenReturn(List.of(dataRun(null)));
        when(taxExports.findAllByUserId(userId)).thenReturn(List.of());

        job.purge(NOW);

        verify(storage, never()).delete(any());
    }

    @Test
    @DisplayName("A file that cannot be deleted does not strand the account in the system")
    void anUndeletableFileDoesNotStopTheErasure() {
        oneExpiredMember();
        String key = "exports/" + householdId + "/unwritable.zip";
        when(dataExports.findAllByUserId(userId)).thenReturn(List.of(dataRun(key)));
        when(taxExports.findAllByUserId(userId)).thenReturn(List.of());
        when(storage.delete(key)).thenThrow(new StorageException("read-only volume"));

        assertThat(job.purge(NOW))
            .as("the row would otherwise survive for ever and the member would still be in the "
                + "system, which is the opposite of what they asked for")
            .isEqualTo(1);
        verify(users).eraseAccountPastItsGrace(userId, householdId,
            NOW.minus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("The cutoff is now minus the grace, so a member inside the window is not selected")
    void theCutoffIsNowMinusTheGrace() {
        when(users.findPurgeCandidates(any(), any(Pageable.class))).thenReturn(List.of());

        assertThat(job.purge(NOW)).isZero();

        verify(users).findPurgeCandidates(
            org.mockito.ArgumentMatchers.eq(NOW.minus(Duration.ofDays(7))),
            any(Pageable.class));
    }
}
