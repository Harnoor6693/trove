package finance.trove.taxexport;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every status transition a {@link TaxExportRun} makes, each in its own {@code REQUIRES_NEW}
 * transaction ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9 — the source's
 * {@code ReportStatusUpdater}, whose shape §2.9 records as correct and which is kept here).
 *
 * <p><strong>Why {@code REQUIRES_NEW} and not merely {@code @Transactional}.</strong> Two
 * different things depend on it:
 *
 * <ul>
 *   <li>A polling client has to see {@code GENERATING} <em>while</em> the work is running. A
 *       transition joined to the generation's own transaction would not be visible until the
 *       generation finished, at which point the status it announced is already stale.</li>
 *   <li>The {@code FAILED} write happens BECAUSE something threw. If it shared a transaction with
 *       the work that threw, the rollback would eat the very row that records the failure and the
 *       run would sit at {@code GENERATING} for ever with nothing to explain it. This is the same
 *       rule {@code SecurityEventRecorder.record} follows for the same reason.</li>
 * </ul>
 *
 * <p>Every method takes the owning {@code userId} as well as the run id, because the repository
 * base deliberately has no unscoped {@code findById} — the scoping is not ceremony here either,
 * since these are the writes that would otherwise be a way to move another member's run.
 */
@Service
public class TaxExportStatusUpdater {

    /**
     * How much of a failure reason is kept. Long enough to name a cause, short enough that a
     * database column is not a log file.
     */
    static final int MAX_REASON_LENGTH = 500;

    private static final Logger LOG = LoggerFactory.getLogger(TaxExportStatusUpdater.class);

    private final TaxExportRunRepository runs;

    public TaxExportStatusUpdater(TaxExportRunRepository runs) {
        this.runs = runs;
    }

    /**
     * Moves the run to {@code status}, recording {@code reason} when there is one.
     *
     * <p>{@code reason} is truncated and is expected to be a sentence, never a stack trace and
     * never the address or endpoint of anything: it is shown to the member, and a member cannot
     * act on a stack trace but can be misled by a hostname in one.
     *
     * @return {@code true} when a row was actually moved; {@code false} when the run is gone,
     *         which happens legitimately if the member deleted it while it was generating
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean transition(UUID runId, UUID userId, TaxExportStatus status, String reason) {
        return runs.findByIdAndUserId(runId, userId)
            .map(run -> {
                run.transitionTo(status, truncate(reason));
                runs.save(run);
                LOG.debug("Tax export run {} moved to {}", runId, status);
                return true;
            })
            .orElseGet(() -> {
                LOG.debug("Tax export run {} is gone; not moving it to {}", runId, status);
                return false;
            });
    }

    /**
     * Writes the storage key, the byte count and {@code COMPLETE} together.
     *
     * <p>One write rather than two: a split between "the file is at this key" and "the run is
     * complete" is a window in which a crash leaves a run that is complete with no file, or a file
     * no run points at. The first is a 500 on the download; the second is an orphan on the volume.
     *
     * @return {@code true} when a row was updated, {@code false} when the run is gone — the caller
     *         then deletes the file it has just written rather than leaving it unreferenced
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(UUID runId, UUID userId, String storageKey, long sizeBytes) {
        return runs.findByIdAndUserId(runId, userId)
            .map(run -> {
                run.complete(storageKey, sizeBytes);
                runs.save(run);
                LOG.debug("Tax export run {} complete ({} bytes)", runId, sizeBytes);
                return true;
            })
            .orElse(false);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.length() <= MAX_REASON_LENGTH
            ? reason
            : reason.substring(0, MAX_REASON_LENGTH);
    }
}
