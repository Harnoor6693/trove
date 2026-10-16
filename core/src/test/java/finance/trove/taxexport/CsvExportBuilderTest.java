package finance.trove.taxexport;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * §9 risk 15's first half: the CSV carries the member's own amounts and labels and no computed
 * figure, and every cell that a spreadsheet would evaluate is neutralised in the BUILDER, where
 * collapse step 18 inherits it (gap P2-16).
 */
class CsvExportBuilderTest {

    private final CsvExportBuilder builder = new CsvExportBuilder();

    private static ExportRow row(String merchant, String description, String notes,
                                 String amount, CurrencyCode currency, String category,
                                 String lineCode, String lineLabel) {
        return new ExportRow(LocalDate.of(2025, 3, 4), merchant, description, notes,
            new BigDecimal(amount), currency, category, lineCode, lineLabel, null,
            TransactionType.DEBIT, null, null, false);
    }

    private List<CSVRecord> parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8).replaceFirst("^\uFEFF", "");
        try (CSVParser parser = CSVParser.parse(new StringReader(text), CSVFormat.DEFAULT)) {
            return parser.getRecords();
        } catch (Exception e) {
            throw new IllegalStateException("The builder produced unparseable CSV:\n" + text, e);
        }
    }

    @Test
    @DisplayName("The header set is exactly the columns §2.9 fixes (+ type), in order")
    void theHeaderSetIsExact() {
        List<CSVRecord> records = parse(builder.build(List.of()));

        assertThat(records.get(0).toList())
            .as("§2.9 fixes this column list and collapse step 18 reuses it; a column added or "
                + "renamed here changes a file members already parse")
            .containsExactly("date", "merchant", "description", "notes", "amount", "currency",
                "category", "taxLineCode", "taxLineLabel", "receiptId", "type");
    }

    @Test
    @DisplayName("A receipt id reaches the file, and nothing else about the receipt does")
    void aReceiptIdReachesTheFile() {
        // Collapse step 14 added transaction.receipt_id (V38) and TaxExportGenerationService
        // passes it into this last argument, which was a literal null until then. What travels is
        // the ID and only the ID: the receipt's own GST/PST/HST split stays on the receipt and is
        // never exported (§2.9, open question 1).
        UUID receiptId = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
        byte[] content = builder.build(List.of(new ExportRow(LocalDate.of(2025, 3, 4), "Cafe",
            "Latte", "", new BigDecimal("11.30"), CurrencyCode.CAD, "Coffee Shops", "8523",
            "Meals and entertainment", receiptId, TransactionType.DEBIT, null, null, false)));

        List<CSVRecord> records = parse(content);
        assertThat(records.get(1).get(9))
            .as("the receiptId column carries the id of the attached receipt")
            .isEqualTo(receiptId.toString());
        assertThat(new String(content, StandardCharsets.UTF_8))
            .as("no figure computed from the receipt may reach an export")
            .doesNotContain("gst").doesNotContain("hst").doesNotContain("pst");
    }

    @Test
    @DisplayName("No column is derived from a rate: gst, tax_owing and rate are absent")
    void noColumnIsDerivedFromARate() {
        byte[] content = builder.build(List.of(
            row("Cafe", "Latte", "", "10.00", CurrencyCode.CAD, "Coffee Shops", "NONE",
                "No tax line - personal expense")));
        String text = new String(content, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);

        assertThat(text)
            .as("owner decision 2 and §9 risk 15: this export carries amounts and labels and NO "
                + "computed figure. The source computed GST as amount x 0.05 on a tax-INCLUSIVE "
                + "amount (RS-1); the column is deleted, not corrected")
            .doesNotContain("gst")
            .doesNotContain("tax_owing")
            .doesNotContain("rate");
    }

    @Test
    @DisplayName("notes is its own column and is never folded into description (RS-6)")
    void notesIsItsOwnColumn() {
        byte[] content = builder.build(List.of(
            row("Clinic", "Consultation fee", "Ask about the receipt", "120.00", CurrencyCode.CAD,
                "Doctor", "33099", "T1 line 33099 - Medical expenses")));
        List<CSVRecord> records = parse(content);
        List<String> header = records.get(0).toList();
        List<String> data = records.get(1).toList();

        assertThat(data.get(header.indexOf("description")))
            .as("the merchant's own text belongs under description")
            .isEqualTo("Consultation fee");
        assertThat(data.get(header.indexOf("notes")))
            .as("RS-6: the member's PRIVATE notes were exported under a heading that read "
                + "'description', indistinguishable from the merchant's own text in a file the "
                + "member hands to an accountant")
            .isEqualTo("Ask about the receipt");
    }

    @ParameterizedTest(name = "a cell starting with [{0}] is escaped")
    @ValueSource(strings = {"=", "+", "-", "@", "\t", "\r"})
    @DisplayName("Every formula starter is neutralised with a leading apostrophe")
    void everyFormulaStarterIsEscaped(String starter) {
        assertThat(CsvExportBuilder.escape(starter + "SUM(A1:A2)"))
            .as("gap P2-16: a spreadsheet evaluates a cell that begins with %s", starter)
            .isEqualTo("'" + starter + "SUM(A1:A2)");
    }

    @Test
    @DisplayName("A merchant literally called =HYPERLINK(...) round-trips escaped")
    void theHyperlinkMerchantRoundTripsEscaped() {
        String hostile = "=HYPERLINK(\"http://evil\",\"click\")";
        byte[] content = builder.build(List.of(
            row(hostile, "", "", "5.00", CurrencyCode.CAD, "Shopping", "", "")));
        List<CSVRecord> records = parse(content);

        assertThat(records.get(1).get(1))
            .as("the named regression of gap P2-16 and §9 risk 31: this merchant becomes a live "
                + "link in the file the member opens unless the builder neutralises it")
            .isEqualTo("'" + hostile);
    }

    @Test
    @DisplayName("A negative amount is escaped too, deliberately")
    void aNegativeAmountIsEscapedToo() {
        byte[] content = builder.build(List.of(
            row("Refund", "", "", "-12.34", CurrencyCode.CAD, "Shopping", "", "")));

        assertThat(parse(content).get(1).get(4))
            .as("a minus is a formula starter and the rule has no exemption for cells that "
                + "happen to parse as numbers: a second rule about which cells are dangerous is "
                + "a second rule to get wrong. The totals section is computed server-side, so "
                + "the reader does not need the spreadsheet to add the column up")
            .isEqualTo("'-12.3400");
    }

    @Test
    @DisplayName("A value with a comma, a quote and a newline round-trips intact")
    void awkwardTextRoundTrips() {
        String awkward = "Smith, \"The Grocer\"\nUnit 4";
        byte[] content = builder.build(List.of(
            row(awkward, "", "", "9.99", CurrencyCode.CAD, "Groceries", "", "")));

        assertThat(parse(content).get(1).get(1))
            .as("commons-csv quoting has to survive the escaping, and the escaping must not "
                + "corrupt a value that merely contains a separator")
            .isEqualTo(awkward);
    }

    @Test
    @DisplayName("Totals are per (line, currency) and never blended across currencies")
    void totalsArePerLineAndCurrency() {
        byte[] content = builder.build(List.of(
            row("A", "", "", "10.00", CurrencyCode.CAD, "Software", "8810", "Office expenses"),
            row("B", "", "", "20.00", CurrencyCode.CAD, "Software", "8810", "Office expenses"),
            row("C", "", "", "30.00", CurrencyCode.USD, "Software", "8810", "Office expenses"),
            row("D", "", "", "40.00", CurrencyCode.CAD, "Groceries", "", "")));

        List<List<String>> totals = totalsSection(parse(content));

        assertThat(totals)
            .as("findings F1/F2: a household holding two currencies gets one row per currency, "
                + "never one blended total — no exchange rate exists anywhere in this system")
            .containsExactly(
                List.of("", "", "CAD", "40.0000"),
                List.of("8810", "Office expenses", "CAD", "30.0000"),
                List.of("8810", "Office expenses", "USD", "30.0000"));
    }

    @Test
    @DisplayName("Each row says its direction, and totals count spending only (B2-01)")
    void rowsAreLabelledAndTotalsAreExpensesOnly() {
        byte[] content = builder.build(List.of(
            typed("DEBIT", "100.00", TransactionType.DEBIT),
            typed("REFUND", "20.00", TransactionType.REFUND),
            typed("PAYCHEQUE", "3000.00", TransactionType.CREDIT),
            typed("BANK FEE", "5.00", TransactionType.FEE)));

        List<CSVRecord> records = parse(content);
        assertThat(records.subList(1, 5).stream().map(r -> r.get(10)).toList())
            .as("every row carries its transaction type, so income is not mistaken for spend")
            .containsExactly("DEBIT", "REFUND", "CREDIT", "FEE");
        assertThat(totalsSection(records))
            .as("income and fees stay out of the expense total; a refund reduces it")
            .containsExactly(List.of("8810", "Office expenses", "CAD", "80.0000"));
    }

    private static ExportRow typed(String merchant, String amount, TransactionType type) {
        return new ExportRow(LocalDate.of(2025, 3, 4), merchant, "", "", new BigDecimal(amount),
            CurrencyCode.CAD, "Software", "8810", "Office expenses", null, type, null, null, false);
    }

    @Test
    @DisplayName("Totals are BigDecimal sums at the ledger's NUMERIC(19,4) scale")
    void totalsCarryTheLedgerScale() {
        byte[] content = builder.build(List.of(
            row("A", "", "", "0.1", CurrencyCode.CAD, "Software", "8810", "Office"),
            row("B", "", "", "0.2", CurrencyCode.CAD, "Software", "8810", "Office")));

        assertThat(totalsSection(parse(content)).get(0).get(3))
            .as("§9 risk 6: money is BigDecimal at NUMERIC(19,4). A double would answer "
                + "0.30000000000000004 here")
            .isEqualTo("0.3000");
    }

    @Test
    @DisplayName("An unmapped category still produces a row, with empty tax-line cells")
    void anUnmappedCategoryIsStillARow() {
        byte[] content = builder.build(List.of(
            row("Corner shop", "", "", "3.50", CurrencyCode.CAD, "A household's own category",
                "", "")));
        List<CSVRecord> records = parse(content);

        assertThat(records.get(1).get(1)).isEqualTo("Corner shop");
        assertThat(records.get(1).get(7)).as("taxLineCode is empty, not absent").isEmpty();
        assertThat(records.get(1).get(8)).as("taxLineLabel is empty, not absent").isEmpty();
    }

    /** The records after the blank line and the two heading lines of the totals section. */
    private static List<List<String>> totalsSection(List<CSVRecord> records) {
        int title = -1;
        for (int i = 0; i < records.size(); i++) {
            if (CsvExportBuilder.TOTALS_SECTION_TITLE.equals(records.get(i).get(0))) {
                title = i;
                break;
            }
        }
        assertThat(title).as("the totals section must be present").isGreaterThan(0);
        assertThat(records.get(title + 1).toList())
            .isEqualTo(CsvExportBuilder.TOTALS_HEADERS);
        return records.subList(title + 2, records.size()).stream().map(CSVRecord::toList).toList();
    }
}
