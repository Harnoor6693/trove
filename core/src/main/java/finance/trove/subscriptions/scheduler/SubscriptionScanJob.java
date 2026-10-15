package finance.trove.subscriptions.scheduler;

import finance.trove.common.time.UserClock;
import finance.trove.subscriptions.detection.SubscriptionDetectionProperties;
import finance.trove.subscriptions.detection.SubscriptionDetectionService;
import finance.trove.subscriptions.detection.SubscriptionScanTargets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link SubscriptionDetectionService#detectForHousehold} over every household with recent
 * transactions, nightly ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5 item 6, §6 D2).
 *
 * <p>A component of its own rather than a method on {@code PurgeJobs}, for the reason
 * {@code finance.trove.transactions.TransferPairingJob}'s javadoc gives for itself: that class
 * sweeps append-only tables and this one writes ledger rows, so they have different blast radii
 * and belong under different reviews. This class copies that one's shape — a UTC cron plus a
 * distinct ShedLock name.
 *
 * <p><strong>The lock name is FIXED by plan §5 item 6</strong> — {@code "subscription-scan"} — and
 * is not this class's to change. {@code ScheduledJobsTest} scans every compiled class and fails
 * the build on a name shared with any other job anywhere in the application.
 *
 * <p><strong>02:00 UTC, and the zone is written down.</strong> The source's javadoc claimed
 * "02:00 UTC" while its {@code @Scheduled} carried no {@code zone} at all (inventory SD-6), so it
 * actually fired in the container's default zone — drifting by an hour twice a year and, on the
 * spring-forward hour, either firing twice or not at all. §5.6 makes {@code zone = "UTC"}
 * mandatory for the TRIGGER; the per-household dates the scan computes come from
 * {@code UserClock} and are unaffected.
 *
 * <p><strong>It enumerates households with transactions</strong>, never the result table — see
 * {@link SubscriptionScanTargets} for the bootstrap deadlock that made the pre-collapse job
 * incapable of ever detecting a first subscription.
 *
 * <p>Returns {@code void} because ShedLock refuses to wrap a method with a return value — it
 * cannot say what a locked-out invocation should answer. The count goes to the log, and the tests
 * drive {@link #runOnce()}.
 */
@Component
public class SubscriptionScanJob {

    /** Fixed by plan §5 item 6. {@code ScheduledJobsTest} fails the build if this collides. */
    public static final String LOCK_NAME = "subscription-scan";

    /**
     * Slack added to the enumeration window on top of the detection window.
     *
     * <p>The enumeration is a single UTC query while each household's real window is computed in
     * its own timezone, so the two can differ by up to a day at either end. A household whose
     * oldest transaction sits exactly on the boundary must not be skipped because the job asked
     * the question in the wrong zone, and including a household with nothing to detect costs one
     * scan that writes nothing.
     */
    private static final int ENUMERATION_SLACK_DAYS = 2;

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionScanJob.class);

    private final SubscriptionScanTargets targets;
    private final SubscriptionDetectionService detection;
    private final SubscriptionDetectionProperties properties;
    private final UserClock clock;

    public SubscriptionScanJob(SubscriptionScanTargets targets,
                               SubscriptionDetectionService detection,
                               SubscriptionDetectionProperties properties, UserClock clock) {
        this.targets = targets;
        this.detection = detection;
        this.properties = properties;
        this.clock = clock;
    }

    /** 02:00 UTC daily, per plan §5 item 6. */
    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    public void scanSubscriptions() {
        LOG.info("subscription-scan refreshed {} subscription(s)", runOnce());
    }

    /**
     * One pass, exposed so a test can drive it without waiting for a cron.
     *
     * <p>Each household is its own transaction inside
     * {@link SubscriptionDetectionService#detectForHousehold}, and a failure is logged and
     * stepped over: one household's bad data must not cost every household behind it in the loop
     * its nightly scan. This method itself is deliberately not {@code @Transactional} — a single
     * transaction spanning every household on the install would hold one connection for the whole
     * sweep.
     *
     * @return how many subscriptions were written or refreshed across every household
     */
    public int runOnce() {
        LocalDate today = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC);
        LocalDate since = today.minus(properties.detectionWindow())
            .minusDays(ENUMERATION_SLACK_DAYS);

        int refreshed = 0;
        List<UUID> households = targets.householdsWithActivitySince(since);
        for (UUID householdId : households) {
            try {
                refreshed += detection.detectForHousehold(householdId);
            } catch (RuntimeException e) {
                LOG.error("subscription-scan failed for household {}: {}", householdId,
                    e.getMessage(), e);
            }
        }
        return refreshed;
    }
}
