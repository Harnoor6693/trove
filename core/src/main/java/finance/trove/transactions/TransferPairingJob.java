package finance.trove.transactions;

import finance.trove.common.time.UserClock;
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
 * Runs {@link TransferPairingService} over every household that has an unpaired leg
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-14).
 *
 * <p>It is a component of its own rather than a sixth method on {@code PurgeJobs}, deliberately:
 * that class sweeps append-only tables and this one changes ledger rows, so they have different
 * blast radii and belong under different reviews. It copies that class's shape — a UTC cron plus a
 * distinct ShedLock name — because those two decisions are the ones that matter and are easy to
 * get wrong.
 *
 * <p><strong>The trigger is UTC; the content is not per-user.</strong> §5.6 keeps every
 * {@code @Scheduled} on {@code zone = "UTC"} so a scheduler cannot fire twice or not at all across
 * a DST transition. That is unrelated to the per-user boundaries {@code UserClock} computes: the
 * window here is a span of calendar days around "now", not somebody's "today", so it is read from
 * the same clock every other date decision in the application reads from and is not resolved
 * against any one member's timezone.
 *
 * <p><strong>The lock name is distinct</strong>, and that is load-bearing: ShedLock keys on the
 * name alone, so a name shared with one of {@code PurgeJobs}' five would make this job silently
 * never run. {@code ScheduledJobsTest} is what holds that: it scans every compiled class for
 * {@code @Scheduled} and fails on a duplicate lock name, a missing lock or a cron that is not on
 * UTC, anywhere in the application. {@code PurgeJobsTest} could not — it reflects over
 * {@code PurgeJobs} alone, and a collision is by definition a fact about two classes.
 *
 * <p>Returns {@code void} because ShedLock refuses to wrap a method with a return value — it
 * cannot say what a locked-out invocation should answer. The counts go to the log, and the tests
 * assert against the table.
 */
@Component
public class TransferPairingJob {

    /**
     * The ShedLock name. Distinct from every entry of {@code PurgeJobs.LOCK_NAMES}, and
     * {@code ScheduledJobsTest} fails the build if that ever stops being true.
     */
    public static final String LOCK_NAME = "transfer-pairing";

    private static final Logger LOG = LoggerFactory.getLogger(TransferPairingJob.class);

    private final TransferPairingService pairing;
    private final UserClock clock;

    public TransferPairingJob(TransferPairingService pairing, UserClock clock) {
        this.pairing = pairing;
        this.clock = clock;
    }

    /**
     * Hourly at ten past, UTC. Hourly rather than daily because the pair a member is looking for
     * is usually the one they made this morning, and the work is bounded by
     * {@link TransferPairingService#LOOKBACK_DAYS} rather than by the size of the ledger.
     */
    @Scheduled(cron = "0 10 * * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    public void pairTransfers() {
        LOG.info("transfer-pairing linked {} transfer pairs", runOnce());
    }

    /**
     * One pass, exposed so a test can drive it without waiting for a cron.
     *
     * @return how many pairs were linked across every household
     */
    public int runOnce() {
        LocalDate today = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC);
        LocalDate from = today.minusDays(TransferPairingService.LOOKBACK_DAYS);
        // The upper bound reaches past today because a source may report a value date in the
        // near future (a scheduled payment), and a leg outside the window would never be paired.
        LocalDate to = today.plusDays(TransferPairingService.WINDOW_DAYS);

        int pairs = 0;
        List<UUID> households = pairing.householdsWithUnpairedLegs(from, to);
        for (UUID householdId : households) {
            pairs += pairing.pairHousehold(householdId, from, to);
        }
        return pairs;
    }
}
