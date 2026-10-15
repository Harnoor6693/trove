package finance.trove.notifications.digest;

import finance.trove.notifications.NotificationProperties;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.preference.NotificationPreferenceRepository;
import finance.trove.notifications.send.NotificationContent;
import finance.trove.notifications.send.NotificationService;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The Monday digest ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5 item 6, §2.8; gap P2-15;
 * findings D-N1 and F1).
 *
 * <p>Its own component under {@code finance.trove.notifications.digest} rather than a method on
 * {@code PurgeJobs}, following the precedent of {@code TransferPairingJob}: a job belongs with the
 * code it drives, and {@code ScheduledJobsTest} scans every compiled class rather than one.
 *
 * <p><strong>Weekly, not daily.</strong> §5 item 6 and §2.8 both say weekly and the lock name
 * {@code weekly-digest} is fixed by the first of them.
 *
 * <h2>UTC trigger, per-recipient content</h2>
 * The cron fires once, at 08:00 UTC on Monday, on the container clock — {@code zone = "UTC"} is
 * required of every cron in this application and {@code ScheduledJobsTest} fails the build without
 * it. What each recipient is TOLD is computed in their own timezone: {@link WeeklyDigestBuilder}
 * takes the last complete Monday-to-Sunday week from {@code UserClock}, so a Vancouver member and
 * a Toronto member woken by this same instant get different week boundaries. That split is gap
 * P2-15's answer, and {@code WeeklyDigestIT} asserts it with two real recipients.
 *
 * <h2>One recipient's failure does not stop the loop — and does not vanish either</h2>
 * The source caught per user, logged, counted a "skip", and reported success; combined with an
 * ingestion call that could never have worked (defect D-N1) that meant every Monday for the life
 * of the service reported {@code sent=0} and nobody noticed. Here a failure is written to
 * {@code notification_log} as {@code FAILED} with its reason, through
 * {@link NotificationService#recordFailure}, which is {@code REQUIRES_NEW} so the row survives the
 * rollback of whatever failed.
 *
 * <h2>Paged, and interruptible</h2>
 * Recipients are read a page at a time so that an install with ten thousand members does not put
 * them all on the heap, and {@link Thread#isInterrupted()} is checked between recipients (§5 item
 * 13, graceful shutdown): a deploy in the middle of a digest run stops between members rather than
 * being killed inside one. The next run picks the rest up, because the ISO-week dedup key means a
 * member who was already sent one is skipped by V32's unique index rather than by any state this
 * job keeps.
 */
@Component
public class WeeklyDigestJob {

    private static final Logger LOG = LoggerFactory.getLogger(WeeklyDigestJob.class);

    private final NotificationPreferenceRepository preferences;
    private final UserRepository users;
    private final WeeklyDigestBuilder builder;
    private final NotificationService notifications;
    private final NotificationProperties properties;

    public WeeklyDigestJob(NotificationPreferenceRepository preferences, UserRepository users,
                           WeeklyDigestBuilder builder, NotificationService notifications,
                           NotificationProperties properties) {
        this.preferences = preferences;
        this.users = users;
        this.builder = builder;
        this.notifications = notifications;
        this.properties = properties;
    }

    /** Monday 08:00 UTC. The lock name is fixed by §5 item 6 and is the source's. */
    @Scheduled(cron = "0 0 8 * * MON", zone = "UTC")
    @SchedulerLock(name = "weekly-digest", lockAtMostFor = "PT30M")
    public void sendWeeklyDigests() {
        int sent = sendAllDue();
        LOG.info("weekly-digest: {} digests written", sent);
    }

    /**
     * One full run, returning how many digests were written.
     *
     * <p>Separate from the scheduled method because ShedLock refuses to wrap a method with a
     * return value — it cannot say what a locked-out invocation should return — and
     * {@code WeeklyDigestIT} needs the count.
     */
    public int sendAllDue() {
        int page = 0;
        int sent = 0;
        while (true) {
            List<UUID> recipients = preferences.findDigestRecipients(
                PageRequest.of(page, properties.digestPageSize()));
            if (recipients.isEmpty()) {
                return sent;
            }
            for (UUID userId : recipients) {
                if (Thread.currentThread().isInterrupted()) {
                    LOG.info("weekly-digest interrupted after {} digests; the rest are picked up "
                        + "by the next run, which the ISO-week dedup key makes safe", sent);
                    return sent;
                }
                if (sendOne(userId)) {
                    sent++;
                }
            }
            page++;
        }
    }

    /**
     * One recipient.
     *
     * @return whether a digest was written; false when it was already sent, the account has gone,
     *         or the attempt failed (in which case a FAILED row records why)
     */
    boolean sendOne(UUID userId) {
        // Declared before the try, and EVERYTHING that can throw is inside it. The account read
        // and the week computation used to sit above the try, which meant a repository error or a
        // recipient whose stored timezone no longer resolves threw straight out of this method,
        // out of sendAllDue's loop, and abandoned every remaining recipient of that Monday with no
        // row recorded for any of them — the whole-run version of the silent failure D-N1 was.
        LocalDate weekStart = null;
        try {
            Optional<User> found = users.findAccount(userId);
            if (found.isEmpty() || !found.get().isActive()) {
                return false;
            }
            User recipient = found.get();
            weekStart = builder.weekStartFor(userId);
            List<WeeklyDigestSummary> summaries = builder.summarise(recipient.getHouseholdId(),
                recipient.getCurrency(), weekStart);
            NotificationContent content =
                builder.contentFor(weekStart, recipient.getCurrency(), summaries);
            return notifications.notify(userId, NotificationType.WEEKLY_DIGEST, content);
        } catch (Exception e) {
            // swallow-ok: recorded, not dropped. The reason is written to notification_log as a
            // FAILED row (REQUIRES_NEW, so it survives this rollback) and the loop continues —
            // one member's bad data must not cost every other member their digest, and a failure
            // that produced no row at all is exactly how defect D-N1 stayed invisible.
            LOG.warn("weekly digest failed for user {}", userId, e);
            recordFailure(userId, weekStart, e);
            return false;
        }
    }

    /**
     * Writes the FAILED row for one recipient.
     *
     * <p>{@code weekStart} is null when the failure happened BEFORE the week could be computed —
     * an unreadable account row, or a timezone that no longer resolves. The row is still written,
     * with a null dedup key, because "not deduplicated" is the honest description of a failure
     * whose window is unknown; the alternative, inventing a key, would silence the next run.
     *
     * <p>Its own try/catch is the last line of the invariant this class states: if even RECORDING
     * the failure fails — the database is gone, say — the loop must still move to the next
     * recipient rather than take the whole Monday down with it.
     */
    private void recordFailure(UUID userId, LocalDate weekStart, Exception cause) {
        String window = weekStart == null ? "this week" : "the week beginning " + weekStart;
        String key = weekStart == null ? null : WeeklyDigestBuilder.isoWeekOf(weekStart);
        try {
            notifications.recordFailure(userId, NotificationType.WEEKLY_DIGEST,
                NotificationContent.of("Your weekly digest could not be prepared",
                    "The digest for " + window + " could not be built.", key),
                describe(cause));
        } catch (Exception recordingFailed) {
            // swallow-ok: there is nowhere left to record it. Logged at ERROR so that it is
            // visible, and swallowed so that one unrecordable recipient cannot cost every other
            // recipient of this run their digest — which is precisely the failure this method
            // exists to prevent.
            LOG.error("weekly digest failure could not be recorded for user {}", userId,
                recordingFailed);
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
