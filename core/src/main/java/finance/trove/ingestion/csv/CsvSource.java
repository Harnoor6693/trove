package finance.trove.ingestion.csv;

import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.plugin.TransactionType;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.ingestion.CsvColumnMapping;
import finance.trove.ingestion.DateFormatCandidate;
import finance.trove.ingestion.FileImportSource;
import finance.trove.ingestion.ImportConfig;
import finance.trove.ingestion.ImportContext;
import finance.trove.ingestion.ImportMapping;
import finance.trove.ingestion.ParsedRow;
import finance.trove.ingestion.ParsedUpload;
import finance.trove.ingestion.importbatch.ImportLimits;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Imports a bank's CSV export ({@code docs/recovery/inventory/plugins.md} §8.2;
 * {@code docs/recovery/07-READINESS-GAPS.md} P2-12).
 *
 * <p><strong>Entirely new code.</strong> None of the seven pre-collapse plugins implemented CSV,
 * and {@code 01-FINDINGS.md} contains no CSV finding because there was nothing to find.
 *
 * <h2>The deterministic identifier, and why it is not null</h2>
 * A CSV export carries no transaction id of any kind, so plugins.md §8.2 offered two options and
 * recommended leaving {@code provider_transaction_id} null and relying on the fuzzy pass. This
 * step takes the other one, deliberately: every row gets
 * {@code sha256(accountId | date | amount | normalised description | row ordinal)}. Three
 * consequences follow, and they are the reason.
 * <ul>
 *   <li><strong>Re-importing the same file is idempotent.</strong> The hash is the same on the
 *       second parse, the exact-match key finds the stored row, and nothing is inserted twice.
 *       The fuzzy pass could never do that for a CSV: it required a PENDING candidate and a CSV
 *       row arrives already posted, so a re-imported file matched nothing and every row of it was
 *       inserted again (§6 M0, defect 2).</li>
 *   <li><strong>Two identical-looking rows stay two transactions.</strong> The ordinal is in the
 *       hash, so two coffees of the same amount on the same day at the same shop are two rows and
 *       not one — which the ±2 day, ±$0.50 fuzzy window would have collapsed.</li>
 *   <li><strong>It never reaches the fuzzy pass at all</strong>, which is what §6 M0 calls the
 *       real fix for a re-imported file.</li>
 * </ul>
 *
 * <h2>The window is ignored</h2>
 * See {@link FileImportSource}: an uploaded file is exactly the rows the member chose to export,
 * and filtering it against the account's sync window would silently drop the older half of a
 * statement.
 */
@Component
public class CsvSource implements FileImportSource {

    /** How often the parse budget is consulted. Checking every row would cost more than it saves. */
    private static final int BUDGET_CHECK_INTERVAL = 500;

    /** The scale every amount is canonicalised to before hashing — {@code NUMERIC(19,4)}'s. */
    private static final int HASH_SCALE = 4;

    private final CsvHeaderSniffer sniffer;
    private final LocalDiskStorage storage;
    private final ImportLimits limits;
    private final ObjectMapper objectMapper;

    public CsvSource(CsvHeaderSniffer sniffer, LocalDiskStorage storage, ImportLimits limits,
                     ObjectMapper objectMapper) {
        this.sniffer = sniffer;
        this.storage = storage;
        this.limits = limits;
        this.objectMapper = objectMapper;
    }

    @Override
    public SourceKind sourceKind() {
        return SourceKind.CSV;
    }

    /**
     * Re-reads the batch's stored upload at commit time.
     *
     * <p>{@code from} and {@code to} are ignored — see {@link FileImportSource}. An account with no
     * batch configuration has no file attached, which is a failure the member can act on rather
     * than an empty success that looks like "your bank had nothing new".
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
        ImportMapping mapping = readMapping(config.get(ImportConfig.MAPPING));
        UUID accountId = accountIdOf(config);
        ParsedUpload parsed = read(storage.readAllBytes(storageKey), mapping,
            new ImportContext(accountId));
        return parsed.rows().stream().map(ParsedRow::row).toList();
    }

    @Override
    public ParsedUpload read(byte[] content, ImportMapping mapping, ImportContext context) {
        if (context.accountId() == null) {
            throw new BankSourceConnectionException("A CSV file does not say which account its "
                + "rows belong to, so an account has to be named with the upload.");
        }
        CsvHeaderSniffer.Decoded decoded = sniffer.decode(content);
        CsvColumnMapping supplied = mapping == null ? null : mapping.csv();
        char delimiter = supplied != null && supplied.delimiter() != null
            && !supplied.delimiter().isEmpty()
            ? supplied.delimiter().charAt(0)
            : sniffer.detectDelimiter(decoded.text());

        List<CSVRecord> records = parseRecords(decoded.text(), delimiter);
        if (records.isEmpty()) {
            throw new BankSourceConnectionException("This file has no rows at all.");
        }
        List<String> header = cells(records.get(0));
        CsvColumnMapping detected = supplied != null && supplied.dateColumn() != null
            ? supplied
            : sniffer.map(header, decoded.charset(), delimiter);

        List<CSVRecord> data = records.subList(detected.hasHeader() ? 1 : 0, records.size());
        if (data.size() > limits.maxRows()) {
            throw new BankSourceConnectionException("This file has " + data.size() + " rows, and "
                + "this server imports at most " + limits.maxRows() + " in one file.");
        }

        Columns columns = Columns.resolve(header, detected);
        List<String> dateCells = dateCells(data, columns);
        List<DateFormatCandidate> candidates =
            CsvValues.dateFormatCandidates(dateCells, limits.previewSampleValues());
        CsvColumnMapping effective = detected.dateFormat() != null
            ? detected
            : detected.withDateFormat(CsvValues.detectDateFormat(dateCells));

        List<String> warnings = new ArrayList<>();
        String readingFormat = effective.dateFormat();
        if (readingFormat == null && !candidates.isEmpty()) {
            readingFormat = candidates.get(0).format();
            warnings.add("The dates in this file read equally well as " + candidates.get(0).format()
                + " and " + candidates.get(1).format() + ". The preview below uses "
                + readingFormat + "; confirm a format on the commit — the server will not guess.");
        } else if (readingFormat == null && !dateCells.isEmpty()) {
            // Neither a single format that reads every cell nor two that both do: the column is
            // not dates at all, or it is written a way this server does not know. Saying so is the
            // difference between an actionable preview and every row reporting that its date
            // "could not be read as null" (confirmed review finding 4).
            warnings.add("No date format this server knows reads every value in the date column, "
                + "so no row below could be dated. Check that the date column is mapped to the "
                + "right column, or send mapping.dateFormat on the commit with the pattern this "
                + "file uses.");
        }
        if (effective.balanceColumn() != null) {
            warnings.add("A balance column was recognised and is deliberately not imported: a "
                + "running balance is not a transaction.");
        }

        List<ParsedRow> rows = new ArrayList<>(data.size());
        long deadline = System.nanoTime() + limits.parseBudget().toNanos();
        for (int i = 0; i < data.size(); i++) {
            if (i % BUDGET_CHECK_INTERVAL == 0) {
                checkBudget(deadline, limits.parseBudget());
            }
            rows.add(row(data.get(i), i + 1, columns, readingFormat, context.accountId()));
        }
        return new ParsedUpload(rows, null, null, null, null, effective, candidates, warnings);
    }

    /** One data row, always produced — a cell the parser could not read becomes a reason. */
    private ParsedRow row(CSVRecord record, int ordinal, Columns columns, String dateFormat,
                          UUID accountId) {
        String tooLong = columns.tooLongField(record, limits.maxFieldChars());
        if (tooLong != null) {
            return new ParsedRow(ordinal, empty(ordinal), "the " + tooLong + " field is longer "
                + "than the " + limits.maxFieldChars() + " characters this server accepts");
        }
        String rawDate = columns.value(record, columns.date());
        LocalDate date = CsvValues.date(rawDate, dateFormat);
        String description = trimToNull(columns.value(record, columns.description()));
        String currency = trimToNull(columns.value(record, columns.currency()));

        String debit = columns.value(record, columns.debit());
        String credit = columns.value(record, columns.credit());
        BigDecimal amount;
        String error = null;
        if (columns.debit() >= 0 || columns.credit() >= 0) {
            BigDecimal out = CsvValues.amount(debit);
            BigDecimal in = CsvValues.amount(credit);
            if (out != null && in != null) {
                return new ParsedRow(ordinal, empty(ordinal), "this row has both a debit and a "
                    + "credit amount; the file's columns have been read wrongly, and adding the "
                    + "two together would misstate the row");
            }
            amount = out != null ? out.abs().negate() : in;
        } else {
            amount = CsvValues.amount(columns.value(record, columns.amount()));
        }

        if (date == null) {
            if (rawDate == null || rawDate.isBlank()) {
                error = "this row has no date";
            } else if (dateFormat == null) {
                error = "no date format could be established for this file, so this row's date "
                    + "could not be read";
            } else {
                error = "this row's date could not be read as " + dateFormat;
            }
        } else if (amount == null) {
            error = "this row has no amount that could be read as a number: write it like "
                + "-1234.56, 1 234,56 or 45.00 DR, with no exponent, at most 15 digits before the "
                + "decimal point and 4 after it";
        }
        if (error != null) {
            return new ParsedRow(ordinal, new RawTransaction(null, accountId.toString(), amount,
                currency, description, null, date, null, null, false, Map.of()), error);
        }

        TransactionType type = amount.signum() >= 0
            ? TransactionType.CREDIT
            : TransactionType.DEBIT;
        String id = deterministicId(accountId, date, amount, description, ordinal);
        return ParsedRow.of(ordinal, new RawTransaction(id, accountId.toString(), amount.abs(),
            currency, description, null, date, null, type, false, Map.of()));
    }

    /**
     * {@code sha256(accountId | date | amount | normalised description | ordinal)}, hex.
     *
     * <p>Every component is canonicalised before it is hashed, because the hash has to be
     * <em>identical</em> across two parses of the same bytes months apart: the amount to the
     * ledger's own four decimal places, the description upper-cased with its whitespace collapsed,
     * the date as ISO-8601. The ordinal is what keeps two identical-looking rows two transactions.
     */
    static String deterministicId(UUID accountId, LocalDate date, BigDecimal amount,
                                  String description, int ordinal) {
        String material = accountId + "|" + date + "|"
            + amount.setScale(HASH_SCALE, RoundingMode.HALF_UP).toPlainString() + "|"
            + normaliseDescription(description) + "|" + ordinal;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(
                StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }

    private static String normaliseDescription(String description) {
        if (description == null) {
            return "";
        }
        return description.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** A row with nothing in it but its position, for a line the parser could not use at all. */
    private static RawTransaction empty(int ordinal) {
        return new RawTransaction(null, null, null, null, null, null, null, null, null, false,
            Map.of("ordinal", String.valueOf(ordinal)));
    }

    private List<String> dateCells(List<CSVRecord> data, Columns columns) {
        List<String> cells = new ArrayList<>(Math.min(data.size(), limits.maxRows()));
        for (CSVRecord record : data) {
            String cell = columns.value(record, columns.date());
            if (cell != null && !cell.isBlank()) {
                cells.add(cell.trim());
            }
        }
        return cells;
    }

    private List<CSVRecord> parseRecords(String text, char delimiter) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
            .setDelimiter(delimiter)
            .setQuote('"')
            .setIgnoreEmptyLines(true)
            .setIgnoreSurroundingSpaces(true)
            .setTrim(false)
            .get();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            return parser.getRecords();
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw new BankSourceConnectionException(
                "This file could not be read as CSV: " + e.getClass().getSimpleName(), e);
        }
    }

    private ImportMapping readMapping(String json) {
        if (json == null || json.isBlank()) {
            throw new BankSourceConnectionException("This import batch has no column mapping.");
        }
        try {
            return objectMapper.readValue(json, ImportMapping.class);
        } catch (JacksonException e) {
            throw new BankSourceConnectionException(
                "This import batch's stored column mapping could not be read.", e);
        }
    }

    private static UUID accountIdOf(Map<String, String> config) {
        String value = config.get(ImportConfig.ACCOUNT_ID);
        if (value == null) {
            throw new BankSourceConnectionException("This import batch names no account.");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BankSourceConnectionException("This import batch names no valid account.", e);
        }
    }

    private static void checkBudget(long deadlineNanos, Duration budget) {
        if (System.nanoTime() > deadlineNanos) {
            throw new BankSourceConnectionException("This file took longer than "
                + budget.toSeconds() + " seconds to parse and was abandoned.");
        }
    }

    private static List<String> cells(CSVRecord record) {
        List<String> values = new ArrayList<>(record.size());
        for (int i = 0; i < record.size(); i++) {
            values.add(record.get(i));
        }
        return values;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The mapping resolved to column POSITIONS.
     *
     * <p>Positions rather than names because a member's adjustment may address a column by ordinal
     * — a file with no header row has no names — and because looking a name up per cell on fifty
     * thousand rows is fifty thousand string comparisons per column for no benefit.
     */
    private record Columns(int date, int description, int amount, int debit, int credit,
                           int currency) {

        static Columns resolve(List<String> header, CsvColumnMapping mapping) {
            return new Columns(index(header, mapping.dateColumn()),
                index(header, mapping.descriptionColumn()), index(header, mapping.amountColumn()),
                index(header, mapping.debitColumn()), index(header, mapping.creditColumn()),
                index(header, mapping.currencyColumn()));
        }

        String value(CSVRecord record, int column) {
            return column >= 0 && column < record.size() ? record.get(column) : null;
        }

        /** The name of the first mapped field whose cell is over the per-field ceiling. */
        String tooLongField(CSVRecord record, int maxChars) {
            if (over(record, description, maxChars)) {
                return "description";
            }
            if (over(record, date, maxChars)) {
                return "date";
            }
            if (over(record, amount, maxChars) || over(record, debit, maxChars)
                || over(record, credit, maxChars)) {
                return "amount";
            }
            return over(record, currency, maxChars) ? "currency" : null;
        }

        private boolean over(CSVRecord record, int column, int maxChars) {
            String value = value(record, column);
            return value != null && value.length() > maxChars;
        }

        /**
         * The position of a mapped column: its header text, or — for a headerless file, or a
         * member who mapped by position — a decimal ordinal.
         */
        private static int index(List<String> header, String name) {
            if (name == null) {
                return -1;
            }
            int byName = header.indexOf(name);
            if (byName >= 0) {
                return byName;
            }
            int byNormalisedName = indexOfNormalised(header, name);
            if (byNormalisedName >= 0) {
                return byNormalisedName;
            }
            try {
                int ordinal = Integer.parseInt(name.trim());
                return ordinal >= 0 ? ordinal : -1;
            } catch (NumberFormatException e) {
                // swallow-ok: a name that is neither a header nor an ordinal names no column, and
                // an unmapped column is a field the rows simply do not carry.
                return -1;
            }
        }

        private static int indexOfNormalised(List<String> header, String name) {
            String wanted = CsvHeaderSniffer.normalise(name);
            for (int i = 0; i < header.size(); i++) {
                if (CsvHeaderSniffer.normalise(header.get(i)).equals(wanted)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
