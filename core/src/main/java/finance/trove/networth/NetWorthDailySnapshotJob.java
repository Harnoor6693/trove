package finance.trove.networth;

import finance.trove.households.Household;
import finance.trove.households.HouseholdRepository;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link NetWorthSnapshotService#takeSnapshot} over every household, daily
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5 item 6, §6 "Net worth").
 *
 * <p>A component of its own rather than a method on {@code PurgeJobs}, deliberately, for the exact
 * reason {@code finance.trove.transactions.TransferPairingJob}'s javadoc gives for itself: that
 * class sweeps append-only tables and this one writes ledger rows, so they have different blast
 * radii and belong under different reviews. This class copies that one's shape: a UTC cron plus a
 * distinct ShedLock name.
 *
 * <p><strong>The lock name is FIXED by plan §5 item 6</strong> —
 * {@code "networth-daily-snapshot"} — and is not this class's to change; {@code ScheduledJobsTest}
 * fails the build on a name shared with any other job anywhere in the application.
 *
 * <p><strong>Enumerates HOUSEHOLDS, not the union of asset and liability owners.</strong> The
 * pre-collapse {@code NetWorthService#dailySnapshotAll} unioned
 * {@code AssetRepository.findDistinctUserIds()} and {@code LiabilityRepository.findDistinctUserIds()}
 * — a per-user scope that no longer matches the shared-ledger tenancy model (§1.5). Every
 * household is a candidate, whether or not it happens to have assets or liabilities today;
 * {@link NetWorthSnapshotService#takeSnapshot} itself writes nothing for a household with neither
 * (empty currency set, empty result), so this is not wasted work, only a simpler and correct
 * enumeration.
 *
 * <p>The trigger is UTC (§5.6); the household's own administrator's timezone decides the
 * <em>content</em> — see {@link NetWorthSnapshotService}'s javadoc for why and for the
 * consequence of that choice.
 */
@Component
public class NetWorthDailySnapshotJob {

    /** Fixed by plan §5 item 6. {@code ScheduledJobsTest} fails the build if this collides. */
    public static final String LOCK_NAME = "networth-daily-snapshot";

    private static final Logger LOG = LoggerFactory.getLogger(NetWorthDailySnapshotJob.class);

    private final HouseholdRepository households;
    private final NetWorthSnapshotService snapshotService;

    public NetWorthDailySnapshotJob(HouseholdRepository households,
                                    NetWorthSnapshotService snapshotService) {
        this.households = households;
        this.snapshotService = snapshotService;
    }

    /** 23:30 UTC daily, per plan §5 item 6. */
    @Scheduled(cron = "0 30 23 * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    public void takeSnapshots() {
        int processedCount = runOnce();
        LOG.info("networth-daily-snapshot processed {} households", processedCount);
    }

    /**
     * One pass, exposed so a test can drive it without waiting for a cron.
     *
     * <p>Each household's snapshot is its own {@code REQUIRES_NEW} transaction (inside
     * {@link NetWorthSnapshotService#takeSnapshot}), so one household's failure cannot roll back
     * another's, or leave this loop's own (absent) transaction in a broken state — there is none:
     * this method itself is not {@code @Transactional}.
     *
     * @return how many households were processed (attempted, whether or not any row resulted)
     */
    public int runOnce() {
        List<UUID> ids =
            households.findAllByOrderByCreatedAtAsc().stream().map(Household::getId).toList();
        int processed = 0;
        for (UUID householdId : ids) {
            try {
                snapshotService.takeSnapshot(householdId, null);
                processed++;
            } catch (RuntimeException e) {
                LOG.error("networth-daily-snapshot failed for household {}: {}", householdId,
                    e.getMessage(), e);
            }
        }
        return processed;
    }
}
