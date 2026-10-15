package finance.trove.ingestion.importbatch;

import finance.trove.common.time.Instants;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps import batches that were previewed and then abandoned, and the files they uploaded
 * (confirmed review finding 3).
 *
 * <p><strong>The gap this closes.</strong> The {@code UPLOADS} storage area's own javadoc says a
 * raw CSV/OFX upload is "retained only while an import batch can still be undone", but the only
 * deletion in the tree was {@code ImportService.undo}, which requires a COMMITTED batch. A batch
 * that stayed in PREVIEW — the member changed their mind, or the commit was refused — could never
 * reach it, so its bytes stayed on the shared data volume for ever, with nothing anywhere else
 * sweeping the area. That is an unbounded growth path open to every authenticated member, on the
 * same volume the readiness probe watches for free space.
 *
 * <p>A job of its own rather than a sixth method on {@code PurgeJobs}, following
 * {@code finance.trove.transactions.TransferPairingJob}: the sweep belongs with the feature whose
 * rows and files it removes, and {@code ScheduledJobsTest} enforces the three properties that
 * actually matter — a distinct ShedLock name, a lock at all, and a cron on UTC — across every job
 * in the application rather than within one class.
 */
@Component
public class ImportRetentionJob {

    /**
     * The ShedLock name. Distinct from every entry of {@code PurgeJobs.LOCK_NAMES} and from
     * {@code TransferPairingJob.LOCK_NAME}; {@code ScheduledJobsTest} fails the build if that ever
     * stops being true.
     */
    public static final String LOCK_NAME = "import-preview-sweep";

    private static final Logger LOG = LoggerFactory.getLogger(ImportRetentionJob.class);

    private final ImportService imports;
    private final ImportLimits limits;

    public ImportRetentionJob(ImportService imports, ImportLimits limits) {
        this.imports = imports;
        this.limits = limits;
    }

    /**
     * Daily at 04:05 UTC — after {@code PurgeJobs}' five sweeps and clear of them.
     *
     * <p>Daily rather than hourly because the window it enforces is measured in days: a file that
     * is one hour past {@code trove.import.preview-retention} costs nothing, and a sweep that
     * walks the uploads area is filesystem work.
     */
    @Scheduled(cron = "0 5 4 * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT10M")
    public void purgeAbandonedPreviews() {
        LOG.info("import-preview-sweep removed {} preview batch(es) older than {}", runOnce(),
            limits.previewRetention());
    }

    /**
     * One pass, exposed so a test can drive it without waiting for a cron.
     *
     * @return how many abandoned PREVIEW batches — and their stored uploads — were removed
     */
    public int runOnce() {
        return imports.purgeAbandonedPreviews(
            Instants.now().minus(limits.previewRetention()));
    }
}
