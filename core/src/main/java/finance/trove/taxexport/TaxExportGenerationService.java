package finance.trove.taxexport;

import finance.trove.app.AsyncConfig;
import finance.trove.categorisation.Category;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Turns a {@code PENDING} {@link TaxExportRun} into a file
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9, §6 "Tax export").
 *
 * <h2>The window is a range of DATES, and it is the member's</h2>
 * 1 January to 31 December of the requested year, inclusive
 * ({@link #windowStart}/{@link #windowEnd}). A tax year is a date range and not an instant range,
 * so two members in {@code America/Vancouver} and {@code America/Toronto} asking for the same year
 * get exactly the same window — if they did not, the same transaction would be inside one
 * member's 2025 and outside the other's. The member's own clock decides only "has this year even
 * started yet", which {@link TaxExportService} checks with {@code UserClock.today(userId)} before
 * a run is ever recorded; nothing here reads a zone-less {@code LocalDate.now()} and nothing here
 * uses the container's year (gap P2-15).
 *
 * <h2>The rows are the household's, the run is the member's</h2>
 * The ledger is shared (§1.5), so the file contains the whole household's transactions for the
 * window. The run, the file and the line overrides belong to one member. Both halves are stated in
 * the operation's description because "my tax export" containing a household mate's spending
 * surprises anybody who was not told.
 *
 * <p>The read goes through {@link TransactionQueryService#findSpendableInRange}, which already
 * excludes transfer legs (gap P2-14). Writing the query here instead would be the third place in
 * the tree that has to remember the exclusion, and a credit-card payment counted as both spending
 * and income is invisible until somebody reconciles a statement by hand.
 *
 * <h2>Failure is recorded, not swallowed</h2>
 * Every status write goes through {@link TaxExportStatusUpdater}, whose transitions are
 * {@code REQUIRES_NEW}, so a failure inside the generation still records {@code FAILED} instead of
 * being rolled back with the work that failed. The recorded reason is truncated and is never a
 * stack trace and never the endpoint of anything — this step opens no socket, so there is no
 * endpoint to leak, and keeping the rule explicit is what stops one appearing later.
 */
@Service
public class TaxExportGenerationService {

    private static final Logger LOG = LoggerFactory.getLogger(TaxExportGenerationService.class);

    private final TaxExportRunRepository runs;
    private final TransactionQueryService transactions;
    private final CategoryRepository categories;
    private final TaxLineMappingService lines;
    private final CsvExportBuilder csvBuilder;
    private final PdfExportBuilder pdfBuilder;
    private final LocalDiskStorage storage;
    private final TaxExportStatusUpdater status;

    public TaxExportGenerationService(TaxExportRunRepository runs,
                                      TransactionQueryService transactions,
                                      CategoryRepository categories, TaxLineMappingService lines,
                                      CsvExportBuilder csvBuilder, PdfExportBuilder pdfBuilder,
                                      LocalDiskStorage storage, TaxExportStatusUpdater status) {
        this.runs = runs;
        this.transactions = transactions;
        this.categories = categories;
        this.lines = lines;
        this.csvBuilder = csvBuilder;
        this.pdfBuilder = pdfBuilder;
        this.storage = storage;
        this.status = status;
    }

    /** The first day of the tax year — a date, deliberately not derived from any clock. */
    public static LocalDate windowStart(int taxYear) {
        return LocalDate.of(taxYear, 1, 1);
    }

    /**
     * The last day of the tax year, inclusive.
     *
     * <p>{@code LocalDate.of(year, 12, 31)} rather than "start of next year minus a day" so that
     * the two ends are written the same way, and so that a leap year needs no special case: 2024
     * simply has 366 days between these two dates.
     */
    public static LocalDate windowEnd(int taxYear) {
        return LocalDate.of(taxYear, 12, 31);
    }

    /**
     * Generates one run's file, on the bounded {@code exportExecutor}.
     *
     * <p>Nothing throws out of here on purpose. The executor's uncaught-exception handler logs
     * against no request and no run; catching here means the member sees {@code FAILED} with a
     * reason instead of a run that never moves.
     *
     * @param runId       the row, already committed by {@link TaxExportService}
     * @param userId      the run's owner, and the identity the line map is resolved for
     * @param householdId the ledger to read, and the storage owner the key is filed under
     */
    @Async(AsyncConfig.EXPORT_EXECUTOR)
    public void generateAsync(UUID runId, UUID userId, UUID householdId) {
        try {
            TaxExportRun run = runs.findByIdAndUserId(runId, userId).orElse(null);
            if (run == null) {
                // The member deleted it between the POST and this thread picking it up. Nothing
                // to generate and nothing to record against.
                LOG.debug("Tax export run {} is gone before generation started", runId);
                return;
            }
            if (!status.transition(runId, userId, TaxExportStatus.GENERATING, null)) {
                return;
            }

            List<ExportRow> rows = collectRows(run, userId, householdId);
            byte[] content = switch (run.getFormat()) {
                case CSV -> csvBuilder.build(rows);
                case PDF -> pdfBuilder.build(rows, run.getTaxYear(), run.getJurisdiction());
            };

            // Never java.nio directly: LocalDiskStorage generates the key, refuses to address
            // anything outside its three areas, and that refusal IS the traversal guard.
            String key = storage.put(StorageArea.EXPORTS, householdId, content,
                run.getFormat().extension());
            if (!status.complete(runId, userId, key, content.length)) {
                // The run was deleted while we were writing. Take the file with it rather than
                // leaving bytes on the volume that no row points at.
                storage.delete(key);
            }
        } catch (RuntimeException e) {
            LOG.error("Tax export run {} failed", runId, e);
            recordFailure(runId, userId, e);
        }
    }

    private void recordFailure(UUID runId, UUID userId, RuntimeException cause) {
        try {
            String reason = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
            status.transition(runId, userId, TaxExportStatus.FAILED, reason);
        } catch (RuntimeException secondary) {
            // swallow-ok: the primary failure is already logged above with its stack trace, and
            // there is nothing further this thread can do. Rethrowing would only reach the
            // executor's uncaught handler, which knows less than this line does.
            LOG.error("Could not mark tax export run {} FAILED", runId, secondary);
        }
    }

    /**
     * The window's transactions, each resolved to a category name and a tax line.
     *
     * <p>The category names are read once into a map rather than per row: a year of a busy ledger
     * is thousands of rows over at most a few dozen categories, and a lookup per row is that many
     * round trips for the same handful of answers.
     */
    private List<ExportRow> collectRows(TaxExportRun run, UUID userId, UUID householdId) {
        Map<UUID, TaxLine> resolved = lines.resolve(userId, run.getJurisdiction());
        Map<UUID, String> categoryNames = new HashMap<>();
        for (Category category : categories.findAllVisible(householdId)) {
            categoryNames.put(category.getId(), category.getName());
        }

        List<Transaction> ledger = transactions.findSpendableInRange(householdId,
            windowStart(run.getTaxYear()), windowEnd(run.getTaxYear()));
        List<ExportRow> rows = new ArrayList<>(ledger.size());
        for (Transaction transaction : ledger) {
            if (transaction.isPending()) {
                // A pending charge can change amount or vanish when the posted row arrives, so
                // it does not belong in a figure copied into a return (B2-14).
                continue;
            }
            TaxLine line = TaxLineMappingService.lineFor(resolved, transaction.getCategoryId());
            rows.add(new ExportRow(
                transaction.getDate(),
                transaction.getMerchantName(),
                transaction.getDescription(),
                transaction.getNotes(),
                transaction.getAmount(),
                transaction.getCurrency(),
                categoryNames.getOrDefault(transaction.getCategoryId(), ""),
                line.code(),
                line.label(),
                // Collapse step 14 added transaction.receipt_id (V38), so the column §2.9 fixed
                // is populated now. It is the receipt's ID and nothing else: no download URL, no
                // key, and above all NO figure computed from the receipt — the receipt's own
                // GST/PST/HST split stays on the receipt and is never exported (§2.9, open
                // question 1), which TaxExportIT holds by grepping the generated files.
                transaction.getReceiptId(),
                transaction.getType(), transaction.getAccountId(),
                transaction.getTransferGroupId(), transaction.isPending()));
        }
        return rows;
    }
}
