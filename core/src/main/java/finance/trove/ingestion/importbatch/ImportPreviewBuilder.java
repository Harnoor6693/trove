package finance.trove.ingestion.importbatch;

import finance.trove.accounts.ConnectedAccount;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.ingestion.ParsedRow;
import finance.trove.ingestion.ParsedUpload;
import finance.trove.ingestion.RawTransactionRejectedException;
import finance.trove.ingestion.dedup.DedupBatch;
import finance.trove.ingestion.dedup.DeduplicationResult;
import finance.trove.ingestion.dedup.DeduplicationService;
import finance.trove.ingestion.importbatch.dto.ImportPreviewResponse;
import finance.trove.ingestion.importbatch.dto.ImportPreviewRow;
import finance.trove.ingestion.importbatch.dto.ImportRowVerdict;
import finance.trove.transactions.Transaction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the read-only classifier over a parsed upload and builds the preview body.
 *
 * <h2>Why it is a bean of its own</h2>
 * Confirmed review finding 5. Classification is the one part of an upload that talks to the
 * database, and it used to run with no transaction at all — {@code ImportService.create} is
 * deliberately not transactional — so every row of the file acquired its own connection and ran
 * its own statement: fifty thousand round trips for the largest file this server accepts. Spring's
 * {@code @Transactional} is applied by a proxy, so a self-invocation inside {@code ImportService}
 * would silently do nothing; a separate bean is what actually gets the transaction.
 *
 * <p>The transaction wraps ONLY the classification, and deliberately not the parse or the storage
 * write that precede it: {@code trove.import.parse-budget} allows a file sixty seconds to parse,
 * and holding a connection across that is what {@code SyncOrchestrator}'s javadoc rules out for
 * the same reason.
 *
 * <p>Inside it, the exact pass is one {@link DeduplicationService#exactMatchIndex} — a hundred
 * chunked statements for a fifty-thousand-row file — rather than one statement per row. The rules
 * are untouched: {@code DeduplicationService} still owns them and still decides every verdict.
 */
@Component
class ImportPreviewBuilder {

    private final DeduplicationService deduplication;
    private final ImportLimits limits;

    ImportPreviewBuilder(DeduplicationService deduplication, ImportLimits limits) {
        this.deduplication = deduplication;
        this.limits = limits;
    }

    /**
     * What committing {@code parsed} would do, without writing anything.
     *
     * <p>{@code batch} is a single {@link DedupBatch} for the whole file, so one stored transaction
     * cannot be reported as matching two different incoming rows and the counts always add up to
     * {@code rowCount}.
     */
    @Transactional(readOnly = true)
    public ImportPreviewResponse build(ImportBatch batch, ConnectedAccount account,
                                       ParsedUpload parsed) {
        List<RawTransaction> rows = parsed.rows().stream().map(ParsedRow::row).toList();
        DedupBatch dedupBatch = new DedupBatch(rows);
        Map<String, Transaction> exactMatches = deduplication.exactMatchIndex(account, rows);
        List<ImportPreviewRow> sample = new ArrayList<>(limits.previewRows());
        int newCount = 0;
        int duplicateCount = 0;
        int fuzzyCount = 0;
        int invalidCount = 0;

        for (ParsedRow parsedRow : parsed.rows()) {
            ImportRowVerdict verdict;
            String reason = parsedRow.error();
            if (reason != null) {
                verdict = ImportRowVerdict.INVALID;
            } else {
                try {
                    DeduplicationResult result = deduplication.classify(account, parsedRow.row(),
                        dedupBatch, exactMatches);
                    verdict = verdictOf(result, parsedRow.row().hasProviderId());
                } catch (RawTransactionRejectedException e) {
                    verdict = ImportRowVerdict.INVALID;
                    reason = e.getMessage();
                }
            }
            switch (verdict) {
                case NEW -> newCount++;
                case DUPLICATE -> duplicateCount++;
                case FUZZY_DUPLICATE -> fuzzyCount++;
                case INVALID -> invalidCount++;
                default -> throw new IllegalStateException("Unhandled verdict " + verdict);
            }
            if (sample.size() < limits.previewRows()) {
                sample.add(previewRow(parsedRow, account, verdict, reason));
            }
        }
        return new ImportPreviewResponse(batch.getId(), batch.getHouseholdId(),
            batch.getAccountId(), batch.getSourceKind(), batch.getStatus(),
            batch.getMappingJson() == null ? null : batch.getMappingJson().originalFilename(),
            parsed.rows().size(), newCount, duplicateCount, fuzzyCount, invalidCount,
            parsed.mapping(), parsed.dateFormatCandidates(), sample, parsed.warnings(),
            batch.getCreatedAt());
    }

    /**
     * A verdict of {@code SKIPPED} or {@code UPDATED} means the row is already held; whether the
     * match was certain is the difference between the two duplicate verdicts, and it is decided by
     * whether the row carried an identifier of its own.
     */
    static ImportRowVerdict verdictOf(DeduplicationResult result, boolean hasProviderId) {
        if (result == DeduplicationResult.NEW) {
            return ImportRowVerdict.NEW;
        }
        return hasProviderId ? ImportRowVerdict.DUPLICATE : ImportRowVerdict.FUZZY_DUPLICATE;
    }

    private static ImportPreviewRow previewRow(ParsedRow parsedRow, ConnectedAccount account,
                                               ImportRowVerdict verdict, String reason) {
        RawTransaction row = parsedRow.row();
        return new ImportPreviewRow(parsedRow.ordinal(), row.date(), row.description(),
            row.merchantName(), row.amount(), currencyOf(row.currency(), account), row.type(),
            verdict, reason);
    }

    /**
     * The row's own currency where the file states a supported one, otherwise the account's.
     *
     * <p>Confirmed review finding 6: the unsupported-code branch used to return {@code null} while
     * saying in its own comment that it showed the account's currency, so a blank cell and a
     * {@code GBP} cell — which mean the same thing to this server — rendered differently. Both now
     * render the account's code, which is the currency those rows would actually be judged in.
     */
    static CurrencyCode currencyOf(String declared, ConnectedAccount account) {
        if (declared == null || declared.isBlank()) {
            return account.getCurrency();
        }
        try {
            return CurrencyCode.valueOf(declared.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // swallow-ok: a currency this install does not support is shown as the account's own
            // in the preview and is refused with its own reason at commit time; the preview's job
            // is to report what would happen, not to reject.
            return account.getCurrency();
        }
    }
}
