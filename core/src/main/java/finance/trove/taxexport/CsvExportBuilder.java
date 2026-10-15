package finance.trove.taxexport;

import finance.trove.common.domain.CurrencyCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

/**
 * Renders {@link ExportRow}s as a UTF-8 CSV document
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9, §6; gap P2-16).
 *
 * <p><strong>This builder is also collapse step 18's.</strong> {@code POST /v1/me/export}'s full
 * data export is specified to be a filtered view of the same file, which is why the column list
 * and — critically — the formula-injection escaping live HERE and not in a controller. A second
 * copy of the escaping is a second chance to forget it.
 *
 * <h2>The columns are fixed</h2>
 * {@code date, merchant, description, notes, amount, currency, category, taxLineCode,
 * taxLineLabel, receiptId}, in that order, and {@code CsvExportBuilderTest} asserts the set
 * exactly. {@code notes} is its OWN column (RS-6). There is <strong>no {@code gst_amount} column
 * and no computed figure of any kind</strong>: the source computed GST as {@code amount x 0.05}
 * on a tax-INCLUSIVE bank amount, which overstates it on every line of every export (the correct
 * form is {@code x 5/105}), and its own test asserted the wrong answer as correct (RS-1 /
 * 01-FINDINGS M7). The fix is not better arithmetic — owner decision 2 and §9 risk 15 mean this
 * export carries the member's own amounts and labels and NO computed figure at all, so the column
 * is deleted and that test is not ported.
 *
 * <h2>Formula injection</h2>
 * A spreadsheet treats a cell beginning with an equals sign, a plus, a minus, an at sign, a tab
 * or a carriage return as a formula, so a merchant literally called
 * {@code =HYPERLINK("http://evil","click")} becomes a live link in the file the member opens
 * (gap P2-16). Every cell — not merely the text ones — gets a leading apostrophe in that case,
 * which spreadsheets read as "the rest is literal text".
 *
 * <p>That deliberately includes a NEGATIVE AMOUNT, which begins with a minus: it is written
 * {@code '-12.3400} and a spreadsheet shows it as text rather than as a number. The alternative —
 * exempting cells that happen to parse as numbers — is a second rule about which cells are
 * dangerous, and the whole value of this one is that there is no such rule to get wrong. The
 * reader does not need the spreadsheet to add the column up either: the totals section below is
 * computed here.
 *
 * <h2>Totals</h2>
 * A totals section follows the transactions: one line per {@code (taxLineCode, currency)} pair,
 * each a plain sum of the member's own amounts. Per currency and never blended — a household
 * holding CAD and USD gets two rows for one line, because no exchange rate exists anywhere in
 * this system (findings F1/F2; gap row 40's rate feed is deferred and is not this step's).
 */
@Component
public class CsvExportBuilder {

    /** The column list §2.9 fixes. {@code CsvExportBuilderTest} asserts this set exactly. */
    public static final List<String> HEADERS = List.of("date", "merchant", "description", "notes",
        "amount", "currency", "category", "taxLineCode", "taxLineLabel", "receiptId", "type");

    /** The heading above the per-line totals. */
    public static final String TOTALS_SECTION_TITLE = "Totals by tax line";

    /** The totals section's own column list. */
    public static final List<String> TOTALS_HEADERS =
        List.of("taxLineCode", "taxLineLabel", "currency", "total");

    /** What a spreadsheet reads as the start of a formula: = + - @ tab carriage-return. */
    private static final String FORMULA_STARTERS = "=+-@\t\r";

    /** The scale every ledger amount is stored and summed at, from {@code NUMERIC(19,4)}. */
    private static final int LEDGER_SCALE = 4;

    /** Builds the whole document. Never returns null. */
    public byte[] build(List<ExportRow> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer,
                 CSVFormat.DEFAULT.builder().setHeader(HEADERS.toArray(String[]::new)).get())) {
            for (ExportRow row : rows) {
                printer.printRecord(transactionRecord(row));
            }
            printTotals(printer, rows);
        } catch (IOException e) {
            // A ByteArrayOutputStream does no I/O, so this is unreachable in practice. It is
            // rethrown rather than swallowed so that it can never become a silently truncated
            // file that a member hands to an accountant.
            throw new UncheckedIOException("Could not render the export as CSV", e);
        }
        return out.toByteArray();
    }

    private static List<String> transactionRecord(ExportRow row) {
        return List.of(
            escape(text(row.date())),
            escape(text(row.merchant())),
            escape(text(row.description())),
            escape(text(row.notes())),
            escape(amount(row.amount())),
            escape(row.currency() == null ? "" : row.currency().name()),
            escape(text(row.category())),
            escape(text(row.taxLineCode())),
            escape(text(row.taxLineLabel())),
            escape(text(row.receiptId())),
            escape(text(row.type())));
    }

    private void printTotals(CSVPrinter printer, List<ExportRow> rows) throws IOException {
        printer.println();
        printer.printRecord(List.of(escape(TOTALS_SECTION_TITLE)));
        printer.printRecord(TOTALS_HEADERS);
        for (LineTotal total : totalsByLineAndCurrency(rows)) {
            printer.printRecord(List.of(
                escape(total.code()),
                escape(total.label()),
                escape(total.currency().name()),
                escape(amount(total.total()))));
        }
    }

    /**
     * One total per {@code (taxLineCode, currency)}, ordered by code then currency.
     *
     * <p>An unmapped row's code is the empty string and it gets its own total, which is what makes
     * "how much of my year has no tax line at all?" answerable from the file itself rather than by
     * subtracting the other lines from a number that is not in it.
     */
    static List<LineTotal> totalsByLineAndCurrency(List<ExportRow> rows) {
        Map<String, LineTotal> accumulated = new LinkedHashMap<>();
        for (ExportRow row : rows) {
            if (row.amount() == null || row.currency() == null || row.type() == null) {
                continue;
            }
            // Spending only (B2-01): a debit adds, a refund subtracts, and income, fees,
            // interest and the rest are listed per row but never added into an expense total.
            BigDecimal signed = switch (row.type()) {
                case DEBIT -> row.amount();
                case REFUND -> row.amount().negate();
                default -> null;
            };
            if (signed == null) {
                continue;
            }
            String code = text(row.taxLineCode());
            String key = code + ' ' + row.currency().name();
            LineTotal running = accumulated.get(key);
            BigDecimal sum = running == null ? signed : running.total().add(signed);
            accumulated.put(key,
                new LineTotal(code, text(row.taxLineLabel()), row.currency(), sum));
        }
        List<LineTotal> totals = new ArrayList<>(accumulated.values());
        totals.sort(Comparator.comparing(LineTotal::code)
            .thenComparing(total -> total.currency().name()));
        return List.copyOf(totals);
    }

    /**
     * A plain sum of the member's own amounts for one tax line in one currency.
     *
     * <p>No conversion, no rate, no blended figure: two currencies are two rows.
     */
    record LineTotal(String code, String label, CurrencyCode currency, BigDecimal total) {
    }

    /**
     * Prefixes a cell a spreadsheet would evaluate with an apostrophe (gap P2-16).
     *
     * <p>Package-private so {@code CsvExportBuilderTest} can assert each starter directly, and so
     * that {@link PdfExportBuilder} is not tempted to reach for it: a PDF has no formula
     * evaluator, and escaping there would put an apostrophe in front of a printed merchant name
     * for no reason at all.
     */
    static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return FORMULA_STARTERS.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    /** An amount at the ledger's own scale, in plain notation — never scientific. */
    private static String amount(BigDecimal value) {
        return value == null
            ? ""
            : value.setScale(LEDGER_SCALE, RoundingMode.HALF_UP).toPlainString();
    }
}
