package finance.trove.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.accounts.AccountType;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.accounts.ConnectedAccountRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.events.SyncCompletedEvent.FailureAlert;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.plugin.SourceKind;
import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import finance.trove.transactions.TransactionFixtures;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ScheduledSyncJob#runOnce()} — the admin switch, the claim a manual sync may hold, the
 * actor, and the healthy-to-failing transition that decides whether a failure is announced.
 */
@ExtendWith(MockitoExtension.class)
class ScheduledSyncJobTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-15T09:17:00Z");

    @Mock
    private HouseholdRepository households;

    @Mock
    private ConnectedAccountRepository accounts;

    @Mock
    private SyncLogRepository logs;

    @Mock
    private UserRepository users;

    @Mock
    private SyncOrchestrator orchestrator;

    private ScheduledSyncJob job() {
        return new ScheduledSyncJob(households, accounts, logs, users, orchestrator);
    }

    private void switchedOn(boolean on) {
        Household household = TransactionFixtures.withId(new Household("H", false), HOUSEHOLD);
        household.setScheduledSyncEnabled(on);
        when(households.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(household));
    }

    private static ConnectedAccount account(SourceKind kind, UUID creator) {
        return TransactionFixtures.withId(new ConnectedAccount(HOUSEHOLD, creator, kind, "Chequing",
            "Bank", AccountType.CHECKING, CurrencyCode.CAD, NOW), UUID.randomUUID());
    }

    private void lastRunWas(ConnectedAccount account, SyncRunStatus... newestFirst) {
        List<SyncLog> runs = java.util.Arrays.stream(newestFirst).map(status -> {
            SyncLog log = new SyncLog(HOUSEHOLD, account.getId(), account.getUserId(),
                SourceKind.SIMPLEFIN, NOW);
            if (status != SyncRunStatus.RUNNING) {
                log.complete(status, 0, 0, 0, 0, 0, null, NOW);
            }
            return log;
        }).toList();
        when(logs.findTop50ByHouseholdIdAndAccountIdOrderByStartedAtDesc(HOUSEHOLD,
            account.getId())).thenReturn(runs);
    }

    private static SyncLog finished(SyncRunStatus status) {
        SyncLog log = new SyncLog(HOUSEHOLD, UUID.randomUUID(), UUID.randomUUID(),
            SourceKind.SIMPLEFIN, NOW);
        log.complete(status, 0, 0, 0, 0, 0, null, NOW);
        return log;
    }

    @Test
    @DisplayName("The lock name is unique and stable")
    void lockNameIsFixed() {
        assertThat(ScheduledSyncJob.LOCK_NAME).isEqualTo("scheduled-bank-sync");
    }

    @Test
    @DisplayName("With the admin switch off (the default) the run touches no account at all")
    void aSwitchedOffInstallIsSkipped() {
        switchedOn(false);

        ScheduledSyncJob.Result result = job().runOnce();

        assertThat(result).isEqualTo(new ScheduledSyncJob.Result(0, 0, 0));
        verifyNoInteractions(accounts, orchestrator);
    }

    @Test
    @DisplayName("Only SimpleFIN accounts are synced; manual, CSV and OFX accounts are left alone")
    void onlySimplefinAccountsAreSynced() {
        switchedOn(true);
        UUID owner = UUID.randomUUID();
        ConnectedAccount bank = account(SourceKind.SIMPLEFIN, owner);
        when(accounts.findAllByHouseholdIdOrderByCreatedAtDesc(HOUSEHOLD)).thenReturn(List.of(
            account(SourceKind.MANUAL, owner), account(SourceKind.CSV, owner),
            account(SourceKind.OFX, owner), bank));
        lastRunWas(bank, SyncRunStatus.SUCCESS);
        when(orchestrator.syncScheduled(HOUSEHOLD, owner, bank.getId(), FailureAlert.ONCE))
            .thenReturn(finished(SyncRunStatus.SUCCESS));

        assertThat(job().runOnce()).isEqualTo(new ScheduledSyncJob.Result(1, 0, 0));
    }

    @Test
    @DisplayName("A manual sync holding the claim is a skip for that account, not a failure")
    void aConflictSkipsTheAccountAndTheRunGoesOn() {
        switchedOn(true);
        UUID owner = UUID.randomUUID();
        ConnectedAccount busy = account(SourceKind.SIMPLEFIN, owner);
        ConnectedAccount free = account(SourceKind.SIMPLEFIN, owner);
        when(accounts.findAllByHouseholdIdOrderByCreatedAtDesc(HOUSEHOLD))
            .thenReturn(List.of(busy, free));
        lastRunWas(busy);
        lastRunWas(free);
        when(orchestrator.syncScheduled(any(), any(), eq(busy.getId()), any()))
            .thenThrow(new ConflictException("This account is already being synchronised."));
        when(orchestrator.syncScheduled(any(), any(), eq(free.getId()), any()))
            .thenReturn(finished(SyncRunStatus.SUCCESS));

        assertThat(job().runOnce()).isEqualTo(new ScheduledSyncJob.Result(1, 0, 1));
    }

    @Test
    @DisplayName("A healthy account that fails is announced once; one already failing is not")
    void theFailureTransitionDecidesTheAlert() {
        switchedOn(true);
        UUID owner = UUID.randomUUID();
        ConnectedAccount healthy = account(SourceKind.SIMPLEFIN, owner);
        ConnectedAccount failing = account(SourceKind.SIMPLEFIN, owner);
        ConnectedAccount neverRun = account(SourceKind.SIMPLEFIN, owner);
        when(accounts.findAllByHouseholdIdOrderByCreatedAtDesc(HOUSEHOLD))
            .thenReturn(List.of(healthy, failing, neverRun));
        // A run still RUNNING (a crash mid-run) is not an outcome; the one before it decides.
        lastRunWas(healthy, SyncRunStatus.RUNNING, SyncRunStatus.SUCCESS, SyncRunStatus.FAILED);
        lastRunWas(failing, SyncRunStatus.FAILED, SyncRunStatus.SUCCESS);
        lastRunWas(neverRun);
        when(orchestrator.syncScheduled(any(), any(), any(), any()))
            .thenReturn(finished(SyncRunStatus.FAILED));

        assertThat(job().runOnce()).isEqualTo(new ScheduledSyncJob.Result(0, 3, 0));

        verify(orchestrator).syncScheduled(HOUSEHOLD, owner, healthy.getId(), FailureAlert.ONCE);
        verify(orchestrator).syncScheduled(HOUSEHOLD, owner, failing.getId(), FailureAlert.NONE);
        verify(orchestrator).syncScheduled(HOUSEHOLD, owner, neverRun.getId(), FailureAlert.ONCE);
    }

    @Test
    @DisplayName("A partial failure counts as failing, so the next failure is not announced again")
    void aPartialFailureIsNotHealthy() {
        switchedOn(true);
        UUID owner = UUID.randomUUID();
        ConnectedAccount partial = account(SourceKind.SIMPLEFIN, owner);
        when(accounts.findAllByHouseholdIdOrderByCreatedAtDesc(HOUSEHOLD))
            .thenReturn(List.of(partial));
        lastRunWas(partial, SyncRunStatus.PARTIAL_FAILURE);
        when(orchestrator.syncScheduled(HOUSEHOLD, owner, partial.getId(), FailureAlert.NONE))
            .thenReturn(finished(SyncRunStatus.PARTIAL_FAILURE));

        assertThat(job().runOnce()).isEqualTo(new ScheduledSyncJob.Result(0, 1, 0));
    }

    @Test
    @DisplayName("An account whose creator is gone runs as the household admin, as net worth does")
    void anOrphanedAccountRunsAsTheAdmin() {
        switchedOn(true);
        UUID member = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(HOUSEHOLD))
            .thenReturn(List.of(user(member, UserRole.MEMBER), user(admin, UserRole.ADMIN)));
        ConnectedAccount orphan = account(SourceKind.SIMPLEFIN, null);
        when(accounts.findAllByHouseholdIdOrderByCreatedAtDesc(HOUSEHOLD))
            .thenReturn(List.of(orphan));
        lastRunWas(orphan);
        when(orchestrator.syncScheduled(HOUSEHOLD, admin, orphan.getId(), FailureAlert.ONCE))
            .thenReturn(finished(SyncRunStatus.SUCCESS));

        assertThat(job().runOnce()).isEqualTo(new ScheduledSyncJob.Result(1, 0, 0));
    }

    @Test
    @DisplayName("An unexpected error on one account is a failure for it alone")
    void anUnexpectedErrorDoesNotStopTheRun() {
        switchedOn(true);
        UUID owner = UUID.randomUUID();
        ConnectedAccount broken = account(SourceKind.SIMPLEFIN, owner);
        ConnectedAccount fine = account(SourceKind.SIMPLEFIN, owner);
        when(accounts.findAllByHouseholdIdOrderByCreatedAtDesc(HOUSEHOLD))
            .thenReturn(List.of(broken, fine));
        lastRunWas(broken);
        lastRunWas(fine);
        when(orchestrator.syncScheduled(any(), any(), eq(broken.getId()), any()))
            .thenThrow(new IllegalStateException("boom"));
        when(orchestrator.syncScheduled(any(), any(), eq(fine.getId()), any()))
            .thenReturn(finished(SyncRunStatus.SUCCESS));

        assertThat(job().runOnce()).isEqualTo(new ScheduledSyncJob.Result(1, 1, 0));
        verify(orchestrator, never()).sync(any(), any(), any());
    }

    private static User user(UUID id, UserRole role) {
        return TransactionFixtures.withId(new User(HOUSEHOLD, role, id + "@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneOffset.UTC), id);
    }
}
