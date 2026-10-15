package finance.trove.dataexport;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every number the full data export can be tuned by, in one place (collapse step 18; §1.6).
 *
 * <p><strong>Every value carries its own default here and nothing is added to
 * {@code application.yml}</strong>, which this step may not edit: steps of a wave run in parallel
 * worktrees and a property added to that file is a merge conflict at best and a silently lost edit
 * at worst. A record bound by {@code @ConfigurationPropertiesScan} with defaults in its canonical
 * constructor needs no yml entry to work and picks one up the moment an operator adds one — the
 * shape {@code ReceiptProperties} and {@code NotificationProperties} already use. The keys are
 * named in this step's merge notes so an operator can promote them.
 *
 * <p>The prefix is {@code trove.data-export}, so Spring's relaxed binding maps it to
 * {@code TROVE_DATA_EXPORT_*} — {@code TROVE_DATA_EXPORT_DOWNLOAD_TTL} and
 * {@code TROVE_DATA_EXPORT_RUNS_PER_DAY}.
 *
 * @param downloadTtl   how long a completed archive stays downloadable. Gap row 16 asks for "a
 *                      single 24-hour download link" and this is that 24 hours. After it the
 *                      download answers 409 and {@link DataExportSweep} deletes the bytes: an
 *                      archive of somebody's whole financial life must not sit on a volume for
 *                      ever because nobody fetched it
 * @param runsPerDay    how many runs one member may start in their OWN local day. Gap row 16 asks
 *                      for one. It is enforced in the service and not in the rate limiter, which
 *                      buckets by path and client address rather than by member and so cannot
 *                      express this at all
 * @param sweepBatchSize how many lapsed archives one sweep pass deletes. Bounded so that a volume
 *                      with a long backlog is drained over several passes rather than in one
 *                      transaction that holds a connection while it does filesystem work
 */
@ConfigurationProperties("trove.data-export")
public record DataExportProperties(Duration downloadTtl, Integer runsPerDay,
                                   Integer sweepBatchSize) {

    /** Gap row 16's "single 24-hour download link". */
    public static final Duration DEFAULT_DOWNLOAD_TTL = Duration.ofHours(24);

    /** Gap row 16's "1/day". */
    public static final int DEFAULT_RUNS_PER_DAY = 1;

    /** Enough that an ordinary install drains in one pass, small enough to stay a short job. */
    public static final int DEFAULT_SWEEP_BATCH_SIZE = 200;

    public DataExportProperties {
        downloadTtl = downloadTtl == null ? DEFAULT_DOWNLOAD_TTL : downloadTtl;
        runsPerDay = runsPerDay == null ? DEFAULT_RUNS_PER_DAY : runsPerDay;
        sweepBatchSize = sweepBatchSize == null ? DEFAULT_SWEEP_BATCH_SIZE : sweepBatchSize;

        if (downloadTtl.isNegative() || downloadTtl.isZero()) {
            throw new IllegalArgumentException(
                "trove.data-export.download-ttl must be positive; zero would make every archive "
                    + "expire before the member who asked for it could fetch it. Got "
                    + downloadTtl);
        }
        if (runsPerDay < 1) {
            throw new IllegalArgumentException(
                "trove.data-export.runs-per-day must be at least 1; zero would refuse every "
                    + "export on an install that thinks the feature is switched on. Got "
                    + runsPerDay);
        }
        if (sweepBatchSize < 1) {
            throw new IllegalArgumentException(
                "trove.data-export.sweep-batch-size must be at least 1; zero would make the "
                    + "sweep run for ever and delete nothing. Got " + sweepBatchSize);
        }
    }
}
