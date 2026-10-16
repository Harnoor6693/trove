package finance.trove.ingestion;

import finance.trove.accounts.ConnectedAccount;
import finance.trove.accounts.ConnectedAccountRepository;
import finance.trove.common.events.SyncCompletedEvent.FailureAlert;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.plugin.SourceKind;
import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Syncs every SimpleFIN-connected account on a schedule, once an admin has switched it on
 * (C-01r; {@code households.scheduled_sync_enabled}, V45).
 *
 * <p>It adds no pipeline of its own. Each account goes through
 * {@link SyncOrchestrator#syncScheduled}, the same claim, window and {@code last_synced_at} rule as
 * the Sync button, so a scheduled run and a manual one cannot drift apart. It copies the shape of
 * {@code SubscriptionScanJob}: a UTC cron, a unique ShedLock name, and a {@link #runOnce()} that
 * tests drive directly.
 *
 * <p><strong>The cadence is configuration</strong> ({@code trove.simplefin.scheduled-sync-cron},
 * daily by default). {@code application.yml} cites SimpleFIN Bridge's own guide for why daily:
 * the bridge refreshes once a day and asks for at most 24 requests a day.
 *
 * <p><strong>{@code lockAtMostFor} is thirty minutes.</strong> Accounts are synced one after
 * another, and one bridge call can take the connect timeout plus the read timeout
 * ({@code trove.simplefin.*-timeout-ms}, 5 s + 30 s), so thirty minutes covers about fifty
 * accounts whose bridge never answers. Should a run outlast it anyway, a second node starting
 * early is harmless: every account it reaches is still claimed, and a claim is a skip.
 *
 * <p><strong>Who the run acts as</strong> decides the window's timezone and who is told on
 * failure: the account's creator ({@code connected_account.user_id}); if that member has been
 * erased (the column is {@code ON DELETE SET NULL}), the household's first admin, or its first
 * member if it has none, by the rule {@code NetWorthSnapshotService} uses.
 */
@Component
public class ScheduledSyncJob {

    /** {@code ScheduledJobsTest} fails the build if this collides with another job's name. */
    public static final String LOCK_NAME = "scheduled-bank-sync";

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledSyncJob.class);

    private final HouseholdRepository households;
    private final ConnectedAccountRepository accounts;
    private final SyncLogRepository logs;
    private final UserRepository users;
    private final SyncOrchestrator orchestrator;

    public ScheduledSyncJob(HouseholdRepository households, ConnectedAccountRepository accounts,
                            SyncLogRepository logs, UserRepository users,
                            SyncOrchestrator orchestrator) {
        this.households = households;
        this.accounts = accounts;
        this.logs = logs;
        this.users = users;
        this.orchestrator = orchestrator;
    }

    /**
     * How one pass went. {@code synced} counts runs that finished {@code SUCCESS}; {@code failed}
     * counts {@code PARTIAL_FAILURE}, {@code FAILED} and unexpected exceptions; {@code skipped}
     * counts accounts another sync already held (409) or whose household has no member to act as.
     */
    public record Result(int synced, int failed, int skipped) {
    }

    @Scheduled(cron = "${trove.simplefin.scheduled-sync-cron:0 17 9 * * *}", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M")
    public void syncConnectedAccounts() {
        Result result = runOnce();
        LOG.info("scheduled-bank-sync: {} synced, {} failed, {} skipped", result.synced(),
            result.failed(), result.skipped());
    }

    /**
     * One pass over every household that has the switch on.
     *
     * <p>Each account is its own run with its own transactions, and nothing one account does can
     * stop the loop: a manual sync holding the claim is a skip, any other exception is a failure
     * for that account alone. Deliberately not {@code @Transactional}, for the reason
     * {@link SyncOrchestrator}'s javadoc gives.
     */
    public Result runOnce() {
        int synced = 0;
        int failed = 0;
        int skipped = 0;
        for (Household household : households.findAllByOrderByCreatedAtAsc()) {
            if (!household.isScheduledSyncEnabled()) {
                continue;
            }
            UUID householdId = household.getId();
            UUID admin = null;
            for (ConnectedAccount account
                : accounts.findAllByHouseholdIdOrderByCreatedAtDesc(householdId)) {
                if (account.getSourceKind() != SourceKind.SIMPLEFIN) {
                    continue;
                }
                UUID actor = account.getUserId();
                if (actor == null) {
                    admin = admin != null ? admin : householdAdminId(householdId);
                    actor = admin;
                }
                if (actor == null) {
                    LOG.warn("scheduled-bank-sync skipped account {}: its household has no "
                        + "members to act as", account.getId());
                    skipped++;
                    continue;
                }
                try {
                    SyncLog run = orchestrator.syncScheduled(householdId, actor, account.getId(),
                        failureAlert(householdId, account.getId()));
                    if (run.getStatus() == SyncRunStatus.SUCCESS) {
                        synced++;
                    } else {
                        failed++;
                    }
                } catch (ConflictException e) {
                    LOG.info("scheduled-bank-sync skipped account {}: {}", account.getId(),
                        e.getMessage());
                    skipped++;
                } catch (RuntimeException e) {
                    LOG.error("scheduled-bank-sync failed for account {}", account.getId(), e);
                    failed++;
                }
            }
        }
        return new Result(synced, failed, skipped);
    }

    /**
     * Whether a failure of the coming run is news.
     *
     * <p>The account was healthy if its last finished run — manual or scheduled — succeeded, or if
     * it has never run. Only then is a failure announced ({@code ONCE}); an account that is
     * already failing has had its alert, and a dead connection must not repeat it every day. A
     * run still {@code RUNNING} (a crash mid-run) is not an outcome, so it is passed over.
     */
    private FailureAlert failureAlert(UUID householdId, UUID accountId) {
        return logs.findTop50ByHouseholdIdAndAccountIdOrderByStartedAtDesc(householdId, accountId)
            .stream()
            .filter(log -> log.getStatus() != SyncRunStatus.RUNNING)
            .findFirst()
            .filter(last -> last.getStatus() != SyncRunStatus.SUCCESS)
            .map(last -> FailureAlert.NONE)
            .orElse(FailureAlert.ONCE);
    }

    /** The first ADMIN, else the first member — {@code NetWorthSnapshotService}'s rule. */
    private UUID householdAdminId(UUID householdId) {
        List<User> members = users.findAllByHouseholdIdOrderByCreatedAtAsc(householdId);
        return members.stream().filter(member -> member.getRole() == UserRole.ADMIN)
            .map(User::getId).findFirst()
            .orElseGet(() -> members.stream().map(User::getId).findFirst().orElse(null));
    }
}
