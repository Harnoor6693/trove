package finance.trove.dataexport;

import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.time.Instants;
import java.time.Instant;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes an archive whose 24-hour download link has lapsed (collapse step 18; gap row 16's
 * "single 24-hour download link"; §5.6's {@code export-link-expiry-sweep} in spirit).
 *
 * <p><strong>The expiry is enforced, not merely displayed.</strong> The download already refuses a
 * lapsed run with a 409, but a refusal that leaves the bytes on the volume is a promise the
 * product has not kept: the archive is a complete copy of somebody's financial life, and "it is no
 * longer reachable through the API" is not the same as "it is gone". This job is the second half,
 * and without it the first half would be theatre.
 *
 * <p>The file goes first and the row's key second, and the order matters: the other way round can
 * leave a row pointing at bytes that are gone, which is a 500 on a download that raced the sweep.
 * This order can at worst leave a file the next pass picks up again, because the row still names
 * it.
 *
 * <p><strong>The row itself is kept.</strong> A member should be able to see that they exported on
 * a date and that the link has since lapsed; deleting the row would make their own export history
 * silently shorter every day. Only the bytes go, and {@code fileSizeBytes} goes with them so the
 * response never advertises an archive that is not there.
 *
 * <p>Its own component rather than a method on {@code PurgeJobs}, and its own ShedLock name.
 * {@code ScheduledJobsTest} scans every compiled class and fails the build on a duplicate lock
 * name, an unlocked scheduled method or a cron with no UTC zone — the trigger is UTC, while what
 * the job computes is an absolute instant and needs no member's timezone at all.
 */
@Component
public class DataExportSweep {

    /** This job's ShedLock name; distinct from every other in the application. */
    public static final String LOCK_NAME = "data-export-expiry-sweep";

    private static final Logger LOG = LoggerFactory.getLogger(DataExportSweep.class);

    private final DataExportRunRepository runs;
    private final LocalDiskStorage storage;
    private final DataExportProperties properties;

    public DataExportSweep(DataExportRunRepository runs, LocalDiskStorage storage,
                           DataExportProperties properties) {
        this.runs = runs;
        this.storage = storage;
        this.properties = properties;
    }

    /**
     * Hourly at 12 minutes past, on UTC.
     *
     * <p>Hourly rather than daily because the link is 24 hours: a daily sweep would leave an
     * expired archive on disk for up to another day, which is most of a second lifetime for a file
     * whose whole point is that it is short-lived.
     */
    @Scheduled(cron = "0 12 * * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    public void sweepExpiredArchives() {
        LOG.info("{} deleted {} expired export archives", LOCK_NAME, sweep(Instants.now()));
    }

    /**
     * One bounded pass, taking the instant so a test can drive it without waiting an hour.
     *
     * <p><strong>Deliberately not {@code @Transactional}.</strong> Two reasons, and the second is
     * the one that matters. It does filesystem work, which has no business holding a pooled
     * database connection while it runs. And one archive that cannot be deleted must not roll back
     * the keys already cleared for every other member — each {@code save} below is its own short
     * transaction, so a pass that fails half way through has still done half the work and the next
     * pass finishes it. (An annotation here would also be inert: the scheduled method above calls
     * this one on {@code this}, which no proxy can intercept.)
     *
     * @return how many archives were removed from the volume
     */
    public int sweep(Instant now) {
        List<DataExportRun> lapsed =
            runs.findLapsedArchives(now, PageRequest.of(0, properties.sweepBatchSize()));
        int deleted = 0;
        for (DataExportRun run : lapsed) {
            String key = run.getFileKey();
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                // swallow-ok: a file that cannot be deleted must not stop the pass, or one bad
                // key would keep every other member's expired archive on the volume for ever.
                // The key is never logged — it is a path — only the run it belongs to.
                LOG.error("Could not delete the archive of data export run {}", run.getId(), e);
                continue;
            }
            run.forgetFile();
            runs.save(run);
            deleted++;
        }
        return deleted;
    }
}
