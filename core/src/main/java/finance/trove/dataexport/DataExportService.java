package finance.trove.dataexport;

import finance.trove.auth.StepUpVerifier;
import finance.trove.auth.dto.StepUpRequest;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.time.UserClock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lifecycle of a member's full-data-export runs (collapse step 18; gap row 16).
 *
 * <p><strong>Every read and write here is scoped to one member, never to the household.</strong>
 * The archive is a copy of the caller's own account, so a household mate asking for another
 * member's run gets the same answer as a stranger: 404, never 403 — an answer that never confirms
 * another member's id is real. That is the opposite of every ledger route in the tree, and it is
 * why {@link DataExportRunRepository} extends {@code UserOwnedRepository}.
 */
@Service
public class DataExportService {

    private static final Logger LOG = LoggerFactory.getLogger(DataExportService.class);

    private final DataExportRunRepository runs;
    private final DataExportRunStarter starter;
    private final DataExportGenerationService generation;
    private final StepUpVerifier stepUp;
    private final LocalDiskStorage storage;
    private final UserClock clock;

    public DataExportService(DataExportRunRepository runs, DataExportRunStarter starter,
                             DataExportGenerationService generation, StepUpVerifier stepUp,
                             LocalDiskStorage storage, UserClock clock) {
        this.runs = runs;
        this.starter = starter;
        this.generation = generation;
        this.stepUp = stepUp;
        this.storage = storage;
        this.clock = clock;
    }

    /**
     * Verifies the step-up, records a run and dispatches its generation.
     *
     * <p><strong>Deliberately not {@code @Transactional}.</strong> The INSERT has to be COMMITTED
     * before the background thread looks for the row, and a transaction that is still open when
     * {@code generateAsync} is dispatched is a race the worker loses by finding nothing. Step 15
     * made the same choice for the same reason and this step keeps it.
     *
     * <p>The cap and the INSERT are therefore NOT here. They are one decision, and a decision
     * split across two uncommitted-in-between statements is not a cap at all, so both live behind
     * a household-row lock in {@link DataExportRunStarter#start} — its own bean because a
     * {@code @Transactional} method invoked on {@code this} is not proxied and would run with no
     * transaction and no lock while looking as though it had both.
     *
     * <p>If the executor refuses the work — {@code AbortPolicy}, a full queue — the row is flipped
     * to {@code FAILED} synchronously, before this method returns, so the caller always receives a
     * real, queryable id with an honest status rather than a run that will never move. The
     * response is still 202: the request was accepted and recorded; it is the work that was not.
     *
     * <p>The step-up runs FIRST, before any row is written, so a failed attempt leaves nothing
     * behind but the {@code LOGIN_FAIL} the verifier records, and cannot spend the member's
     * one-per-day allowance.
     */
    public DataExportRun request(UUID userId, UUID householdId, StepUpRequest stepUpRequest) {
        stepUp.verify(userId, stepUpRequest);
        DataExportRun run = starter.start(userId, householdId, clock.now());
        try {
            generation.generateAsync(run.getId(), userId, householdId);
        } catch (RuntimeException e) {
            // The message is a fixed sentence, not the exception's: a rejection message names the
            // executor and its queue depth, which tells a member nothing and an attacker
            // something.
            LOG.warn("Data export run {} could not be queued", run.getId(), e);
            run.transitionTo(DataExportStatus.FAILED, "The export could not be queued");
            runs.save(run);
        }
        return run;
    }

    /** One page of this member's runs, newest first. */
    @Transactional(readOnly = true)
    public PagedResult<DataExportRun> list(UUID userId, int page, int limit) {
        Page<DataExportRun> found = runs.findAllByUserId(userId,
            PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PagedResult<>(found.getContent(), found.getTotalElements(), page, limit);
    }

    /** One of this member's runs; anybody else's id, a household mate's included, is 404. */
    @Transactional(readOnly = true)
    public DataExportRun get(UUID userId, UUID runId) {
        return runs.findByIdAndUserId(runId, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Data export run", runId));
    }

    /**
     * The bytes of a completed, unexpired run, with the file's name.
     *
     * <p>A run that is not downloadable is a 409, not a 404. The run exists and belongs to the
     * caller; answering "no such run" would tell a polling client to discard state it should keep,
     * and would be untrue. {@code PENDING}, {@code GENERATING}, {@code FAILED} and a lapsed
     * 24-hour link all land here, and the message says which — but never says where the file was.
     *
     * <p>Not {@code @Transactional}: {@link #get} manages its own read, and reading a file off the
     * volume is I/O that has no business holding a pooled database connection open.
     */
    public DownloadableArchive download(UUID userId, UUID runId) {
        DataExportRun run = get(userId, runId);
        Instant now = clock.now();
        if (!run.isDownloadable(now)) {
            throw new ConflictException(explain(run, now));
        }
        return new DownloadableArchive(filenameFor(run), storage.readAllBytes(run.getFileKey()));
    }

    /** Why there is nothing to send — in the member's terms, naming nothing on disk. */
    private static String explain(DataExportRun run, Instant now) {
        if (run.getStatus() == DataExportStatus.FAILED) {
            return "Data export run " + run.getId() + " failed and has no archive to download. "
                + "Start a new one.";
        }
        if (!run.getStatus().canHaveFile()) {
            return "Data export run " + run.getId() + " is " + run.getStatus()
                + " and has no archive to download yet. Keep polling this run.";
        }
        if (run.getExpiresAt() != null && !now.isBefore(run.getExpiresAt())) {
            return "The download link for data export run " + run.getId() + " expired at "
                + run.getExpiresAt() + " and the archive has been deleted. Start a new one.";
        }
        return "Data export run " + run.getId() + " has no archive to download.";
    }

    /**
     * The download's filename: built from the run's own id and nothing a caller supplied, so it
     * can carry no path separator and no quote to escape — and no email address, because a
     * downloaded archive of somebody's finances should not name them in a downloads folder.
     */
    static String filenameFor(DataExportRun run) {
        return "trove-data-export-" + run.getId() + ".zip";
    }

    /**
     * A completed archive, ready to stream.
     *
     * @param filename the {@code Content-Disposition} name
     * @param content  the archive's bytes, read from the data volume — nothing is presigned and no
     *                 storage URL is ever handed out
     */
    public record DownloadableArchive(String filename, byte[] content) {
    }
}
