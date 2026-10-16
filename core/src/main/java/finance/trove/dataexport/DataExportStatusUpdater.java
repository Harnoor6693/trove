package finance.trove.dataexport;

import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every status transition a {@link DataExportRun} makes, each in its own {@code REQUIRES_NEW}
 * transaction — the shape {@code TaxExportStatusUpdater} established and this step reuses rather
 * than reinvents.
 *
 * <p><strong>Why {@code REQUIRES_NEW} and not merely {@code @Transactional}.</strong> Two
 * different things depend on it:
 *
 * <ul>
 *   <li>A polling client has to see {@code GENERATING} <em>while</em> the work is running. A
 *       transition joined to the generation's own transaction would not be visible until the
 *       generation finished, at which point the status it announced is already stale.</li>
 *   <li>The {@code FAILED} write happens BECAUSE something threw. Sharing a transaction with the
 *       work that threw would let the rollback eat the very row that records the failure, and the
 *       run would sit at {@code GENERATING} for ever with nothing to explain it. That is the same
 *       rule {@code SecurityEventRecorder.record} follows, for the same reason.</li>
 * </ul>
 *
 * <p>Every method takes the owning {@code userId} as well as the run id, because the repository
 * base deliberately has no unscoped {@code findById} — and the scoping is not ceremony here
 * either, since these are the writes that would otherwise be a way to move another member's run.
 */
@Service
public class DataExportStatusUpdater {

    /**
     * How much of a failure reason is kept. Long enough to name a cause, short enough that a
     * database column is not a log file.
     */
    static final int MAX_REASON_LENGTH = 500;

    private static final Logger LOG = LoggerFactory.getLogger(DataExportStatusUpdater.class);

    private final DataExportRunRepository runs;

    public DataExportStatusUpdater(DataExportRunRepository runs) {
        this.runs = runs;
    }

    /**
     * Moves the run to {@code status}, recording {@code reason} when there is one.
     *
     * <p>{@code reason} is truncated and is expected to be a sentence. Never a stack trace, never
     * a file path and never the name of anything on disk: it is shown to the member, a member
     * cannot act on a stack trace, and a path in one tells an attacker about the volume.
     * The generation service passes a fixed sentence for every failure rather than the
     * exception's own message, which can carry exactly those things; truncation is the backstop,
     * not the control.
     *
     * @return {@code true} when a row was actually moved; {@code false} when the run is gone,
     *         which happens legitimately if the member's account was deleted mid-generation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean transition(UUID runId, UUID userId, DataExportStatus status, String reason) {
        return runs.findByIdAndUserId(runId, userId)
            .map(run -> {
                run.transitionTo(status, truncate(reason));
                runs.save(run);
                LOG.debug("Data export run {} moved to {}", runId, status);
                return true;
            })
            .orElseGet(() -> {
                LOG.debug("Data export run {} is gone; not moving it to {}", runId, status);
                return false;
            });
    }

    /**
     * Writes the storage key, the byte count, the link expiry and {@code COMPLETE} together.
     *
     * <p>One write rather than several: a split between "the archive is at this key" and "the run
     * is complete" is a window in which a crash leaves a run that is complete with no file, or a
     * file no row points at. The first is a 500 on the download; the second is an orphan on the
     * volume that no sweep can find, because the sweep works from rows.
     *
     * @return {@code true} when a row was updated, {@code false} when the run is gone — the caller
     *         then deletes the archive it has just written rather than leaving it unreferenced
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean complete(UUID runId, UUID userId, String storageKey, long sizeBytes,
                            Instant expiresAt) {
        return runs.findByIdAndUserId(runId, userId)
            .map(run -> {
                run.complete(storageKey, sizeBytes, expiresAt);
                runs.save(run);
                LOG.debug("Data export run {} complete ({} bytes, link lapses {})", runId,
                    sizeBytes, expiresAt);
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
