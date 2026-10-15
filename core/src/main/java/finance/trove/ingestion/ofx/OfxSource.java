package finance.trove.ingestion.ofx;

import finance.trove.accounts.AccountType;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.ingestion.FileImportSource;
import finance.trove.ingestion.ImportConfig;
import finance.trove.ingestion.ImportContext;
import finance.trove.ingestion.ImportMapping;
import finance.trove.ingestion.ParsedRow;
import finance.trove.ingestion.ParsedUpload;
import finance.trove.ingestion.importbatch.ImportLimits;
import finance.trove.ingestion.ofx.dto.OfxDocument;
import finance.trove.ingestion.ofx.dto.OfxTransaction;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Imports an OFX 1.x or OFX 2.x file a member uploaded
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.12, §6 "CSV/OFX/QFX import").
 *
 * <p>It replaces {@code OfxPlugin}, and three of that class's decisions do not survive the move:
 * <ul>
 *   <li><strong>The upload base is not a constant.</strong> {@code OfxPlugin} sandboxed file
 *       access to a hardcoded {@code /var/trove/uploads} and re-derived a path-traversal check
 *       around it. The bytes now come from {@link LocalDiskStorage}, which MINTS its own keys and
 *       re-validates every key on the way back in, so there is no caller-controlled path component
 *       for a traversal to be expressed in — the guard is kept, in the one place that owns paths,
 *       rather than reimplemented here.</li>
 *   <li><strong>{@code ACCTID} is used, not discarded.</strong> {@code OfxPlugin} parsed it and
 *       threw it away. It is now {@code connected_account.external_account_id}, which is what makes
 *       re-uploading the same statement reuse the account it created the first time instead of
 *       making a second one and duplicating every transaction on it (§6 M0, blocker).</li>
 *   <li><strong>The date window is ignored.</strong> {@code OfxPlugin} filtered the parsed file
 *       against {@code from}/{@code to}. For an uploaded file that silently drops the older half of
 *       a statement — see {@link FileImportSource}.</li>
 * </ul>
 *
 * <p>What is kept: the first-512-byte SGML/XML detector, the XXE-hardened
 * {@code DocumentBuilderFactory}, and the per-file transaction cap.
 *
 * <p><strong>Known limitation, carried over unchanged.</strong> A file containing several
 * {@code STMTRS} blocks — one statement per account in a single export — is read as ONE account:
 * the first {@code ACCTID} names it and every {@code STMTTRN} in the document is attributed to it.
 * The pre-collapse parser did the same. Splitting a multi-account export into one batch per
 * account is a real feature, not a bug fix, and it is not in this step's scope.
 */
@Component
public class OfxSource implements FileImportSource {

    private final OfxParser parser;
    private final OfxNormalizer normalizer;
    private final LocalDiskStorage storage;
    private final ImportLimits limits;

    public OfxSource(OfxParser parser, OfxNormalizer normalizer, LocalDiskStorage storage,
                     ImportLimits limits) {
        this.parser = parser;
        this.normalizer = normalizer;
        this.storage = storage;
        this.limits = limits;
    }

    @Override
    public SourceKind sourceKind() {
        return SourceKind.OFX;
    }

    /**
     * Re-reads the batch's stored upload at commit time.
     *
     * <p>{@code from} and {@code to} are ignored — see {@link FileImportSource}. So is
     * {@code externalAccountId}: which account this batch writes to was decided when the file was
     * uploaded, either from the file's own {@code ACCTID} or from the {@code accountId} the member
     * sent, and the contract says the member's choice OVERRIDES the file's. Re-deriving it here
     * would refuse a statement a member deliberately filed against an account of their own
     * choosing, and it would protect nothing: the storage key belongs to this batch, so the bytes
     * cannot be some other batch's.
     */
    @Override
    public List<RawTransaction> fetch(String externalAccountId, LocalDate from, LocalDate to,
                                      Map<String, String> config) {
        String storageKey = config.get(ImportConfig.STORAGE_KEY);
        if (storageKey == null || storageKey.isBlank()) {
            throw new BankSourceConnectionException("This account has no uploaded file to import. "
                + "Upload one through POST /v1/imports, which previews it before anything is "
                + "written.");
        }
        return read(storage.readAllBytes(storageKey), null, new ImportContext(null)).rows()
            .stream().map(ParsedRow::toPipeline).toList();
    }

    @Override
    public ParsedUpload read(byte[] content, ImportMapping mapping, ImportContext context) {
        OfxDocument document = parser.parse(content, limits.maxRows());
        List<ParsedRow> rows = new ArrayList<>(document.transactions().size());
        int ordinal = 0;
        for (OfxTransaction transaction : document.transactions()) {
            ordinal++;
            RawTransaction row = normalizer.normalize(document.account(), transaction);
            rows.add(new ParsedRow(ordinal, row, error(row)));
        }
        return new ParsedUpload(rows, document.account().accountId(),
            blankToNull(document.account().bankId()), accountType(document.account().accountType()),
            currency(document.account().currency()), null, List.of(), List.of());
    }

    /**
     * Why a row cannot be imported, or {@code null}. B1-13: the parsers used to drop a row with no
     * {@code FITID} or an unreadable {@code TRNAMT}, so the preview never counted it.
     */
    private static String error(RawTransaction row) {
        if (row.providerTransactionId() == null || row.providerTransactionId().isBlank()) {
            return "this transaction has no FITID, the bank's own id for it, so importing this "
                + "statement again could not recognise it";
        }
        if (row.amount() == null) {
            return "this transaction's TRNAMT is missing or is not a number this server can store "
                + "(no exponent, at most " + Money.MAX_INTEGER_DIGITS + " digits before the "
                + "decimal point and " + Money.MAX_FRACTION_DIGITS + " after it)";
        }
        if (row.date() == null) {
            return "this transaction has neither a DTPOSTED nor a DTUSER date";
        }
        return null;
    }

    /**
     * The OFX {@code ACCTTYPE} as a Trove product type.
     *
     * <p>OFX spells a line of credit {@code CREDITLINE} and a card statement carries no
     * {@code ACCTTYPE} at all, so anything unrecognised is {@code OTHER} rather than a guess —
     * the member renames and reclassifies through {@code PUT /v1/accounts/{id}} anyway, and a
     * wrong guess is harder to notice than an honest one.
     */
    static AccountType accountType(String acctType) {
        if (acctType == null) {
            return AccountType.OTHER;
        }
        return switch (acctType.trim().toUpperCase(Locale.ROOT)) {
            case "CHECKING" -> AccountType.CHECKING;
            case "SAVINGS", "MONEYMRKT" -> AccountType.SAVINGS;
            case "CREDITCARD", "CREDITLINE" -> AccountType.CREDIT_CARD;
            default -> AccountType.OTHER;
        };
    }

    /**
     * {@code CURDEF} as a supported currency, or {@code null}.
     *
     * <p>Null for a code this install does not support, so that account creation falls back to the
     * member's own {@code users.currency} rather than to a literal (finding F4). The individual
     * rows keep the file's code, and the ingestion side refuses one it cannot store rather than
     * reinterpreting a GBP amount as CAD.
     */
    static CurrencyCode currency(String curdef) {
        if (curdef == null || curdef.isBlank()) {
            return null;
        }
        try {
            return CurrencyCode.valueOf(curdef.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // swallow-ok: an unsupported CURDEF is not a reason to refuse the file. The account
            // takes the member's own currency and each row is judged on its own code.
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
