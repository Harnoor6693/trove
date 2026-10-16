package finance.trove.dataexport;

import finance.trove.app.AsyncConfig;
import finance.trove.categorisation.Category;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.common.time.UserClock;
import finance.trove.taxexport.ExportRow;
import finance.trove.taxexport.TaxLine;
import finance.trove.taxexport.TaxLineMappingService;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.time.Instant;
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
 * Turns a {@code PENDING} {@link DataExportRun} into an archive (collapse step 18; gap row 16).
 *
 * <h2>It runs on step 15's executor, not a new one</h2>
 * {@code AsyncConfig.EXPORT_EXECUTOR} — 2 core / 4 max / 50 queued, {@code AbortPolicy}, an
 * MDC-propagating decorator — was added for exactly this family of work and its javadoc says so. A
 * fifth executor for one more kind of export would be a bean, a thread pool and a shutdown path
 * this machine does not need.
 *
 * <h2>The window is the member's whole ledger history</h2>
 * A full data export is not a tax year, so there is nothing to choose and nothing to stamp. The
 * read goes through {@link TransactionQueryService#findAllInRange}, the whole ledger with
 * transfer legs and pending rows included: a user leaving with their data needs every row, not the
 * spending view (B2-03). Only the tax export uses {@code findSpendableInRange}. The bounds are
 * fixed dates well outside any real ledger, because "everything" has no clock question in it; the
 * one place this step does ask a clock is the per-day cap ({@code trove.data-export.runs-per-day}),
 * which {@link DataExportRunStarter} computes (under the household-row lock) over the member's OWN
 * local day (gap row 15).
 *
 * <h2>Failure is recorded, not swallowed</h2>
 * Every status write goes through {@link DataExportStatusUpdater}, whose transitions are
 * {@code REQUIRES_NEW}, so a failure inside the generation still records {@code FAILED} instead of
 * being rolled back with the work that failed. The recorded reason is truncated and is never a
 * stack trace, never a file path and never the name of anything on disk.
 */
@Service
public class DataExportGenerationService {

    /**
     * The earliest date the archive's transaction window starts at.
     *
     * <p>A fixed floor rather than "the member's first transaction": the query is a range scan
     * either way, an extra query to find the floor buys nothing, and a date well before any
     * personal-finance record anybody is exporting cannot exclude a row. It is deliberately not
     * {@code LocalDate.MIN}, which Postgres cannot represent as a {@code date}.
     */
    static final LocalDate LEDGER_EPOCH = LocalDate.of(1900, 1, 1);

    /**
     * The latest date the archive's transaction window ends at.
     *
     * <p>Deliberately NOT "today in the member's timezone". A tax export asks a question about a
     * calendar year, so its bounds are a year; a full data export asks for <em>everything the
     * member has</em>, and a member who entered next month's rent by hand would find it missing
     * from a file that claims to be their whole account. There is therefore no clock question
     * here at all (gap row 15 is about boundaries that MEAN "today"), and the dates inside the
     * archive are each transaction's own stored local date.
     */
    static final LocalDate LEDGER_HORIZON = LocalDate.of(2999, 12, 31);

    private static final Logger LOG = LoggerFactory.getLogger(DataExportGenerationService.class);

    private final DataExportRunRepository runs;
    private final DataExportCollector collector;
    private final DataExportArchiveBuilder archives;
    private final TransactionQueryService transactions;
    private final CategoryRepository categories;
    private final TaxLineMappingService lines;
    private final UserRepository users;
    private final LocalDiskStorage storage;
    private final DataExportStatusUpdater status;
    private final DataExportProperties properties;
    private final UserClock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public DataExportGenerationService(DataExportRunRepository runs,
                                       DataExportCollector collector,
                                       DataExportArchiveBuilder archives,
                                       TransactionQueryService transactions,
                                       CategoryRepository categories, TaxLineMappingService lines,
                                       UserRepository users, LocalDiskStorage storage,
                                       DataExportStatusUpdater status,
                                       DataExportProperties properties, UserClock clock) {
        this.runs = runs;
        this.collector = collector;
        this.archives = archives;
        this.transactions = transactions;
        this.categories = categories;
        this.lines = lines;
        this.users = users;
        this.storage = storage;
        this.status = status;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Generates one run's archive, on the bounded export executor.
     *
     * <p>Nothing throws out of here on purpose. The executor's uncaught-exception handler logs
     * against no request and no run; catching here means the member sees {@code FAILED} with a
     * reason instead of a run that never moves.
     *
     * @param runId       the row, already committed by {@link DataExportService}
     * @param userId      the run's owner, and the identity the coach history and the line map are
     *                    resolved for
     * @param householdId the ledger to read, and the storage owner the key is filed under
     */
    @Async(AsyncConfig.EXPORT_EXECUTOR)
    public void generateAsync(UUID runId, UUID userId, UUID householdId) {
        try {
            if (runs.findByIdAndUserId(runId, userId).isEmpty()) {
                // The member's account was deleted between the POST and this thread picking the
                // run up. Nothing to generate and nothing to record against.
                LOG.debug("Data export run {} is gone before generation started", runId);
                return;
            }
            if (!status.transition(runId, userId, DataExportStatus.GENERATING, null)) {
                return;
            }

            Instant generatedAt = clock.now();
            byte[] content = archives.build(new DataExportArchiveBuilder.ArchiveRequest(
                runId, userId, householdId, generatedAt,
                collectTransactions(userId, householdId),
                collector.collect(userId, householdId)));

            // Never java.nio directly: LocalDiskStorage generates the key, refuses to address
            // anything outside its three areas, and that refusal IS the traversal guard. The
            // second segment is the HOUSEHOLD id because that is the only shape pathOf accepts;
            // ownership of the archive lives on the run row, which is what the download checks.
            String key = storage.put(StorageArea.EXPORTS, householdId, content, "zip");
            Instant expiresAt = generatedAt.plus(properties.downloadTtl());
            if (!status.complete(runId, userId, key, content.length, expiresAt)) {
                // The run went away while we were writing. Take the archive with it rather than
                // leaving a copy of somebody's whole financial life on the volume with no row
                // pointing at it and no sweep able to find it.
                storage.delete(key);
            }
        } catch (RuntimeException e) {
            LOG.error("Data export run {} failed", runId, e);
            recordFailure(runId, userId, e);
        }
    }

    private void recordFailure(UUID runId, UUID userId, RuntimeException cause) {
        try {
            String reason = cause.getMessage() == null || cause.getMessage().isBlank()
                ? cause.getClass().getSimpleName()
                : cause.getMessage();
            status.transition(runId, userId, DataExportStatus.FAILED, reason);
        } catch (RuntimeException secondary) {
            // swallow-ok: the primary failure is already logged above with its stack trace, and
            // there is nothing further this thread can do. Rethrowing would only reach the
            // executor's uncaught handler, which knows less than this line does.
            LOG.error("Could not mark data export run {} FAILED", runId, secondary);
        }
    }

    /**
     * The household's whole spendable history, each row resolved to a category name and a tax
     * line — the exact {@link ExportRow} shape {@code CsvExportBuilder} takes.
     *
     * <p>The category names are read once into a map rather than per row: a household's history is
     * thousands of rows over at most a few dozen categories, and a lookup per row is that many
     * round trips for the same handful of answers.
     */
    private List<ExportRow> collectTransactions(UUID userId, UUID householdId) {
        Jurisdiction jurisdiction = users.findByIdAndHouseholdId(userId, householdId)
            .map(User::getCountry)
            .orElse(null);
        Map<UUID, TaxLine> resolved = jurisdiction == null
            ? Map.of()
            : lines.resolve(userId, jurisdiction);
        Map<UUID, String> categoryNames = new HashMap<>();
        for (Category category : categories.findAllVisible(householdId)) {
            categoryNames.put(category.getId(), category.getName());
        }

        List<Transaction> ledger = transactions.findAllInRange(householdId, LEDGER_EPOCH,
            LEDGER_HORIZON);
        List<ExportRow> rows = new ArrayList<>(ledger.size());
        for (Transaction transaction : ledger) {
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
                transaction.getReceiptId(),
                transaction.getType(), transaction.getAccountId(),
                transaction.getTransferGroupId(), transaction.isPending()));
        }
        return rows;
    }
}
