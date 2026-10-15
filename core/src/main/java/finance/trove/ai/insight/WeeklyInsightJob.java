package finance.trove.ai.insight;

import finance.trove.ai.AiException;
import finance.trove.ai.chat.CoachAudience;
import finance.trove.common.time.UserClock;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Writes every eligible member's weekly note, once a week
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5 item 6, §6; collapse step 13).
 *
 * <h2>Its own class, not a method on PurgeJobs</h2>
 * {@code PurgeJobs} sweeps append-only tables; this one spends a member's money on their own AI
 * endpoint. Different blast radius, different review, different class — the same reasoning
 * {@code TransferPairingJob} was split out under. {@code ScheduledJobsTest} scans every compiled
 * class and fails the build on a duplicate ShedLock name, a missing lock, or a cron without
 * {@code zone = "UTC"}, so this class is covered by it the moment it compiles.
 *
 * <h2>The trigger is UTC; the content is not</h2>
 * §5.6 keeps every {@code @Scheduled} on the container's UTC clock so that a scheduler cannot fire
 * twice or not at all across a DST transition. What each note describes is the RECIPIENT's week,
 * resolved in their own {@code users.timezone} by {@link InsightService#weekStartFor} — readiness
 * gap row 15. Two members of one household in different zones can get different weeks out of this
 * one run, which is the whole reason the split exists.
 *
 * <h2>A second run is free</h2>
 * {@code lockAtMostFor = "PT30M"} means the lock can be released by the clock while its holder is
 * still working, and two nodes can then run this at once. That is not defended against here — it
 * is defended against in the table: {@code UNIQUE (user_id, week_start)} plus
 * {@code INSERT … ON CONFLICT DO NOTHING} makes the second run insert nothing instead of doubling
 * every member's inbox.
 *
 * <h2>One member's failure never stops the run</h2>
 * A member whose endpoint is down, whose key expired, or whose privacy tier refuses the payload is
 * logged and skipped. Their neighbour's note still gets written. The loop also checks for thread
 * interruption between recipients, so a graceful shutdown stops it at a member boundary rather than
 * halfway through somebody's generation (§5 item 13).
 */
@Component
public class WeeklyInsightJob {

    /**
     * The ShedLock name §5 item 6 fixes. Distinct from every other lock in the application, which
     * {@code ScheduledJobsTest} verifies across every compiled class.
     */
    public static final String LOCK_NAME = "weekly-insight";

    /** Recipients per query. Small enough that one page is a short transaction. */
    static final int PAGE_SIZE = 100;

    private static final Logger LOG = LoggerFactory.getLogger(WeeklyInsightJob.class);

    private final InsightRecipients recipients;
    private final InsightService insights;
    private final UserClock clock;

    public WeeklyInsightJob(InsightRecipients recipients, InsightService insights,
                            UserClock clock) {
        this.recipients = recipients;
        this.insights = insights;
        this.clock = clock;
    }

    /** Mondays at 08:30 UTC — §5 item 6's slot, half an hour after the weekly digest's. */
    @Scheduled(cron = "0 30 8 * * MON", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT30M")
    public void generateWeeklyInsights() {
        Result result = runOnce(clock.now());
        LOG.info("weekly-insight wrote {} note(s) for {} recipient(s); {} already had one, "
            + "{} failed", result.written(), result.considered(), result.skipped(),
            result.failed());
    }

    /**
     * One pass, exposed so a test can drive it at a chosen instant without waiting for a cron.
     *
     * @param runAt the instant every recipient's week is resolved against
     */
    public Result runOnce(Instant runAt) {
        int considered = 0;
        int written = 0;
        int skipped = 0;
        int failed = 0;

        for (int offset = 0; ; offset += PAGE_SIZE) {
            List<CoachAudience> page = recipients.page(offset, PAGE_SIZE);
            if (page.isEmpty()) {
                break;
            }
            for (CoachAudience audience : page) {
                if (Thread.currentThread().isInterrupted()) {
                    LOG.warn("weekly-insight interrupted after {} recipient(s); the rest will be "
                        + "picked up by the next run", considered);
                    return new Result(considered, written, skipped, failed);
                }
                considered++;
                try {
                    if (insights.generateFor(audience, runAt)) {
                        written++;
                    } else {
                        skipped++;
                    }
                } catch (AiException e) {
                    // swallow-ok: one member's endpoint being down, rejecting their key, or
                    // refusing the payload under their own privacy tier is that member's
                    // situation, not an outage of the job. Recorded and stepped over.
                    failed++;
                    LOG.warn("weekly-insight could not write a note for one recipient ({}); "
                        + "continuing with the rest", e.getSlug());
                } catch (RuntimeException e) {
                    // swallow-ok: same rule for anything unexpected. The slug-free case is logged
                    // with its throwable because it is a bug rather than somebody's endpoint.
                    failed++;
                    LOG.error("weekly-insight failed unexpectedly for one recipient; continuing "
                        + "with the rest", e);
                }
            }
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        return new Result(considered, written, skipped, failed);
    }

    /**
     * What one pass did.
     *
     * @param considered recipients visited
     * @param written    notes actually inserted
     * @param skipped    recipients who already had a note for their week, or produced none
     * @param failed     recipients whose own endpoint refused or failed
     */
    public record Result(int considered, int written, int skipped, int failed) {
    }
}
