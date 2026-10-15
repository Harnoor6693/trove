package finance.trove.taxexport;

import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.time.UserClock;
import finance.trove.taxexport.dto.CreateTaxExportRunRequest;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lifecycle of a member's export runs
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9, §4.12, §6).
 *
 * <p><strong>Every read and write here is scoped to one member, never to the household.</strong>
 * A tax filing is individual (§1.5, §6), so a household mate asking for another member's run gets
 * the same answer as a stranger: 404. That is the opposite of every other ledger route in the
 * tree, and it is why {@link TaxExportRunRepository} extends {@code UserOwnedRepository}.
 */
@Service
public class TaxExportService {

    /** The earliest year a run may name; matches the request DTO's own {@code @Min}. */
    static final int EARLIEST_TAX_YEAR = 2000;

    private static final Logger LOG = LoggerFactory.getLogger(TaxExportService.class);

    private final TaxExportRunRepository runs;
    private final TaxExportGenerationService generation;
    private final UserRepository users;
    private final UserClock clock;
    private final LocalDiskStorage storage;

    public TaxExportService(TaxExportRunRepository runs, TaxExportGenerationService generation,
                            UserRepository users, UserClock clock, LocalDiskStorage storage) {
        this.runs = runs;
        this.generation = generation;
        this.users = users;
        this.clock = clock;
        this.storage = storage;
    }

    /**
     * Records a run and dispatches its generation.
     *
     * <p><strong>Deliberately not {@code @Transactional}.</strong> The INSERT has to be COMMITTED
     * before the background thread looks for the row, and a transaction that is still open when
     * {@code generateAsync} is dispatched is a race the worker loses by finding nothing. The
     * source made the same choice for the same reason and §2.9 keeps it.
     *
     * <p>If the executor refuses the work — {@code AbortPolicy}, a full queue — the row is flipped
     * to {@code FAILED} synchronously, before this method returns, so the caller always receives a
     * real, queryable id with an honest status rather than a run that will never move. The
     * response is still 202: the request was accepted and recorded; it is the work that was not.
     *
     * <p>The jurisdiction is taken from {@code users.country} and stamped on the row. It is not a
     * request field, so a completed run stays reproducible from its own row after the member
     * changes country.
     */
    public TaxExportRun request(UUID userId, UUID householdId,
                                CreateTaxExportRunRequest request) {
        User member = users.findByIdAndHouseholdId(userId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
        int taxYear = validYear(userId, request.taxYear());
        Jurisdiction jurisdiction = member.getCountry();

        TaxExportRun run = runs.save(
            new TaxExportRun(userId, taxYear, jurisdiction, request.format()));
        try {
            generation.generateAsync(run.getId(), userId, householdId);
        } catch (RuntimeException e) {
            // The message is a fixed sentence, not the exception's: a rejection message names the
            // executor and its queue depth, which tells a member nothing and an attacker
            // something.
            LOG.warn("Tax export run {} could not be queued", run.getId(), e);
            run.transitionTo(TaxExportStatus.FAILED, "Export generation could not be queued");
            runs.save(run);
        }
        return run;
    }

    /**
     * The year, checked against the member's OWN calendar.
     *
     * <p>The upper bound is the current year where the member is, from
     * {@code UserClock.today(userId)} — never {@code LocalDate.now()} and never the container's
     * year (gap P2-15). On 1 January a member in Auckland is a year ahead of one in Vancouver, and
     * refusing the first their own current year would be plainly wrong to them.
     *
     * <p>The current year is allowed even though it has not ended: a member who wants a
     * year-to-date file to hand to an accountant in November is asking a reasonable question.
     */
    private int validYear(UUID userId, Integer requested) {
        int currentYear = clock.today(userId).getYear();
        if (requested == null || requested < EARLIEST_TAX_YEAR || requested > currentYear) {
            throw new ValidationException("taxYear must be between " + EARLIEST_TAX_YEAR + " and "
                + currentYear + " in your own timezone");
        }
        return requested;
    }

    /** One page of this member's runs, newest first. */
    @Transactional(readOnly = true)
    public PagedResult<TaxExportRun> list(UUID userId, int page, int limit) {
        Page<TaxExportRun> found = runs.findAllByUserId(userId,
            PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "createdAt")));
        return new PagedResult<>(found.getContent(), found.getTotalElements(), page, limit);
    }

    /** One of this member's runs; anybody else's id, a household mate's included, is 404. */
    @Transactional(readOnly = true)
    public TaxExportRun get(UUID userId, UUID runId) {
        return runs.findByIdAndUserId(runId, userId)
            .orElseThrow(() -> ResourceNotFoundException.of("Tax export run", runId));
    }

    /**
     * The bytes of a completed run, with the file's name.
     *
     * <p>A run that is not {@code COMPLETE} is a 409, not a 404. The run exists and belongs to the
     * caller; answering "no such run" would tell a polling client to discard state it should keep,
     * and would be untrue. {@code PENDING}, {@code GENERATING} and {@code FAILED} all land here.
     *
     * <p>Not {@code @Transactional}: {@link #get} manages its own read, and reading a file off
     * the volume is I/O that has no business holding a pooled database connection open.
     */
    public DownloadableExport download(UUID userId, UUID runId) {
        TaxExportRun run = get(userId, runId);
        if (!run.getStatus().isDownloadable() || run.getFileKey() == null) {
            throw new ConflictException("Tax export run " + runId + " is " + run.getStatus()
                + " and has no file to download");
        }
        return new DownloadableExport(run.getFormat(), filenameFor(run),
            storage.readAllBytes(run.getFileKey()));
    }

    /**
     * Removes the run and its file together.
     *
     * <p>The file goes first and the row second: the other order can leave a row pointing at bytes
     * that are gone, which is a 500 on the next download. This order can at worst leave an orphan
     * file, which costs disk and nothing else.
     */
    @Transactional
    public void delete(UUID userId, UUID runId) {
        TaxExportRun run = get(userId, runId);
        if (run.getFileKey() != null) {
            storage.delete(run.getFileKey());
        }
        runs.deleteByIdAndUserId(runId, userId);
    }

    /**
     * The download's filename: built from the run's own year and id and nothing a caller supplied,
     * so it can carry no path separator and no quote to escape.
     */
    static String filenameFor(TaxExportRun run) {
        return "trove-tax-export-" + run.getTaxYear() + "-" + run.getId() + "."
            + run.getFormat().extension();
    }

    /**
     * A completed export, ready to stream.
     *
     * @param format   decides the {@code Content-Type} the route sends, which must be one the
     *                 contract declares for that operation
     * @param filename the {@code Content-Disposition} name
     * @param content  the file's bytes, read from the data volume — nothing is presigned and no
     *                 storage URL is ever handed out (RS-7)
     */
    public record DownloadableExport(TaxExportFormat format, String filename, byte[] content) {
    }
}
