package finance.trove.budgets.rollover;

import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetPeriodRepository;
import finance.trove.common.time.UserClock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link RolloverService} over every {@code ROLLOVER} period whose month has ended and that
 * has not been carried forward yet ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.6 item 6;
 * {@code docs/recovery/07-READINESS-GAPS.md} P2-39).
 *
 * <p>A component of its own rather than a method on {@code PurgeJobs}, and modelled on
 * {@code finance.trove.transactions.TransferPairingJob} for the same stated reason: that class
 * sweeps append-only tables and this one changes ledger rows, so they have different blast radii
 * and belong under different reviews.
 *
 * <h2>Daily, not monthly — and the reason is a bug the source shipped</h2>
 * The pre-collapse {@code RolloverScheduler} fired on cron {@code 0 0 1 1 * *}: 01:00 on the first
 * of the month, once. If the container was down, restarting, or mid-deploy at that minute, every
 * household silently lost that month's carry-forward for ever, because the next fire was a month
 * later and the job's only notion of what to do came from {@code LocalDate.now()}. This one runs
 * daily and asks the DATA what is outstanding: a {@code ROLLOVER} period whose
 * {@code rolled_over_at} is still null and whose month has ended is work, whatever today's date
 * is. Nothing is ever lost to downtime, and a second run inside the same window changes nothing
 * because {@link RolloverService} marks each source period as it carries it.
 *
 * <p>One pass advances each household's chain by one month: the period this run creates for the
 * following month was not in the candidate list this run read, so it is picked up tomorrow. A
 * household that was missed for four months is therefore whole again after four daily runs, with
 * the same final figures either way — the carries are applied in order, oldest first, and none of
 * them can be lost while its source period is still unmarked. Draining a whole backlog in one
 * pass would mean re-querying inside the lock window, which buys a day at the cost of an
 * unbounded loop over data the job does not control.
 *
 * <h2>The trigger is UTC; the boundary is per member</h2>
 * §5.6 keeps every {@code @Scheduled} on {@code zone = "UTC"} so a scheduler cannot fire twice or
 * not at all across a DST transition. Whether a given period's month has actually ended is a
 * different question, and it is answered per row through {@link UserClock} against the member who
 * opened it — a household whose budgets were set up in Vancouver rolls over eight hours after one
 * in Toronto, which is what those members each mean by "the month is over" (gap P2-15). The
 * repository's coarse filter is deliberately generous for exactly this reason; the precise test
 * is here, where the member's zone is known.
 *
 * <h2>The lock name is fixed</h2>
 * {@code envelope-rollover}, assigned by §5.6 item 6. {@code ScheduledJobsTest} scans every
 * compiled class and fails the build on two jobs sharing a lock name, on a {@code @Scheduled}
 * without a lock, and on a cron without {@code zone = "UTC"} — a collision is by definition a fact
 * about two classes, so no single job's own test could catch it.
 *
 * <p>Returns {@code void} because ShedLock refuses to wrap a method with a return value; the
 * counts go to the log and {@link #runOnce()} is what the tests drive.
 */
@Component
public class EnvelopeRolloverJob {

    /** Fixed by {@code 06-COLLAPSE-PLAN.md} §5.6 item 6. Distinct from every other job's. */
    public static final String LOCK_NAME = "envelope-rollover";

    private static final Logger LOG = LoggerFactory.getLogger(EnvelopeRolloverJob.class);

    private final BudgetPeriodRepository periods;
    private final RolloverService rollover;
    private final UserClock clock;

    public EnvelopeRolloverJob(BudgetPeriodRepository periods, RolloverService rollover,
                               UserClock clock) {
        this.periods = periods;
        this.rollover = rollover;
        this.clock = clock;
    }

    /**
     * Daily at 00:40 UTC. Early enough that a household on the far side of the date line sees its
     * carry within hours of its own month ending, and off the hour so it does not queue behind
     * every other daily sweep for the same database.
     */
    @Scheduled(cron = "0 40 0 * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    public void rollEnvelopesForward() {
        LOG.info("envelope-rollover carried {} envelopes forward", runOnce());
    }

    /**
     * One pass, exposed so a test can drive it without waiting for a cron.
     *
     * @return how many envelopes were credited across every household
     */
    public int runOnce() {
        // The first of the current month on the container clock: a coarse upper bound that cannot
        // exclude a period some member's timezone has already finished with. Nobody is more than
        // fourteen hours ahead of UTC, so a month that has ended for anyone has a start date at or
        // before this one.
        LocalDate maxMonth = LocalDate.ofInstant(clock.now(), ZoneOffset.UTC).withDayOfMonth(1);
        List<BudgetPeriod> candidates = periods.findRolloverCandidates(maxMonth);

        int credited = 0;
        for (BudgetPeriod period : candidates) {
            if (!hasEndedFor(period)) {
                continue;
            }
            try {
                credited += rollover.rollForward(period.getHouseholdId(), period.getId());
            } catch (RuntimeException e) {
                // One household's failure must not stop the rest; the marker is only written on
                // success, so this period is simply picked up again tomorrow.
                LOG.error("envelope-rollover failed for period {} of household {}",
                    period.getId(), period.getHouseholdId(), e);
            }
        }
        return credited;
    }

    /**
     * Whether {@code period}'s month is over where the member who opened it lives.
     *
     * <p>A period whose creator has since been deleted ({@code user_id} is {@code ON DELETE SET
     * NULL}) falls back to UTC: the household's budget must still roll forward, and UTC is the
     * container's own clock rather than a guess about somebody who is gone.
     */
    private boolean hasEndedFor(BudgetPeriod period) {
        UUID creator = period.getUserId();
        YearMonth memberMonth = creator != null ? clock.currentMonth(creator)
            : YearMonth.from(LocalDate.ofInstant(clock.now(), ZoneOffset.UTC));
        return memberMonth.isAfter(period.month());
    }
}
