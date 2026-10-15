package finance.trove.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.CsvColumnMapping;
import finance.trove.ingestion.ImportContext;
import finance.trove.ingestion.ImportMapping;
import finance.trove.ingestion.ParsedRow;
import finance.trove.ingestion.ParsedUpload;
import finance.trove.ingestion.importbatch.ImportLimits;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a real bank's CSV export becomes — every case here is something a Canadian or American
 * bank actually writes ({@code docs/recovery/inventory/plugins.md} §8.2).
 */
class CsvSourceTest {

    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private final CsvSource source =
        new CsvSource(new CsvHeaderSniffer(), null, ImportLimits.defaults(), null);

    @Test
    @DisplayName("a plain export maps date, description and a single signed amount")
    void mapsTheCommonShape() {
        ParsedUpload parsed = read("""
            Date,Description,Amount
            2026-09-01,GROCERY STORE,-42.50
            2026-09-02,PAYROLL,1500.00
            """);

        assertThat(parsed.mapping().dateColumn()).isEqualTo("Date");
        assertThat(parsed.mapping().descriptionColumn()).isEqualTo("Description");
        assertThat(parsed.mapping().amountColumn()).isEqualTo("Amount");
        assertThat(parsed.mapping().dateFormat()).isEqualTo("uuuu-MM-dd");
        assertThat(parsed.rows()).hasSize(2).noneMatch(ParsedRow::hasError);

        RawTransaction first = parsed.rows().get(0).row();
        assertThat(first.date()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(first.amount()).isEqualByComparingTo(new BigDecimal("42.50"));
        assertThat(first.type()).isEqualTo(TransactionType.DEBIT);
        assertThat(parsed.rows().get(1).row().type()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    @DisplayName("a UTF-8 byte-order mark does not become part of the first header name")
    void consumesTheByteOrderMark() {
        ParsedUpload parsed = read("﻿Transaction Date,Memo,Amount\n2026-09-01,COFFEE,-4.25\n");

        assertThat(parsed.mapping().dateColumn())
            .as("Excel writes a BOM; left in place it makes every exported file unmappable")
            .isEqualTo("Transaction Date");
        assertThat(parsed.mapping().charset()).isEqualTo("UTF-8");
        assertThat(parsed.rows().get(0).row().description()).isEqualTo("COFFEE");
    }

    @Test
    @DisplayName("quoted fields keep their embedded commas and newlines")
    void handlesQuotedFields() {
        ParsedUpload parsed = read("Date,Description,Amount\n"
            + "2026-09-01,\"SHOP, THE\",-10.00\n"
            + "2026-09-02,\"TWO\nLINES\",-20.00\n");

        assertThat(parsed.rows()).hasSize(2).noneMatch(ParsedRow::hasError);
        assertThat(parsed.rows().get(0).row().description()).isEqualTo("SHOP, THE");
        assertThat(parsed.rows().get(1).row().description()).isEqualTo("TWO\nLINES");
    }

    @Test
    @DisplayName("thousands separators, currency symbols, parentheses and a trailing minus")
    void readsEveryNegativeConventionABankUses() {
        ParsedUpload parsed = read("""
            Date,Description,Amount
            2026-09-01,A,"1,234.56"
            2026-09-02,B,(42.00)
            2026-09-03,C,99.99-
            2026-09-04,D,$5.00
            """);

        assertThat(parsed.rows()).hasSize(4).noneMatch(ParsedRow::hasError);
        assertThat(amounts(parsed)).containsExactly(new BigDecimal("1234.56"),
            new BigDecimal("42.00"), new BigDecimal("99.99"), new BigDecimal("5.00"));
        assertThat(types(parsed)).containsExactly(TransactionType.CREDIT, TransactionType.DEBIT,
            TransactionType.DEBIT, TransactionType.CREDIT);
    }

    @Test
    @DisplayName("a debit/credit pair is netted with the right sign, and a semicolon file works")
    void readsADebitCreditPair() {
        ParsedUpload parsed = read("""
            Posted Date;Details;Withdrawal;Deposit
            2026-09-01;RENT;1200.00;
            2026-09-02;SALARY;;2400.00
            """);

        assertThat(parsed.mapping().delimiter()).isEqualTo(";");
        assertThat(parsed.mapping().amountColumn()).isNull();
        assertThat(parsed.mapping().debitColumn()).isEqualTo("Withdrawal");
        assertThat(parsed.mapping().creditColumn()).isEqualTo("Deposit");
        assertThat(parsed.mapping().hasDebitCreditPair()).isTrue();
        assertThat(types(parsed)).containsExactly(TransactionType.DEBIT, TransactionType.CREDIT);
        assertThat(amounts(parsed)).containsExactly(new BigDecimal("1200.00"),
            new BigDecimal("2400.00"));
    }

    @Test
    @DisplayName("a row with BOTH a debit and a credit is refused, not netted")
    void refusesARowWithBothCells() {
        ParsedUpload parsed = read("""
            Date,Description,Debit,Credit
            2026-09-01,ORDINARY,10.00,
            2026-09-02,IMPOSSIBLE,10.00,10.00
            """);

        assertThat(parsed.rows().get(0).hasError()).isFalse();
        assertThat(parsed.rows().get(1).error())
            .as("adding the two together would misstate the row, and so would picking one")
            .contains("both a debit and a credit");
        assertThat(parsed.rows().get(1).row().providerTransactionId())
            .as("a refused row gets no deduplication id: it must not be able to match anything")
            .isNull();
    }

    @Test
    @DisplayName("dd/MM and MM/dd are offered as candidates rather than guessed between")
    void offersDateFormatCandidatesWhenTheFileIsAmbiguous() {
        ParsedUpload parsed = read("""
            Date,Description,Amount
            03/09/2026,A,-1.00
            11/09/2026,B,-2.00
            """);

        assertThat(parsed.mapping().dateFormat())
            .as("the server does not choose between them")
            .isNull();
        assertThat(parsed.dateFormatCandidates()).extracting("format")
            .containsExactly("dd/MM/uuuu", "MM/dd/uuuu");
        assertThat(parsed.dateFormatCandidates().get(0).interpretedDates())
            .containsExactly(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 11));
        assertThat(parsed.dateFormatCandidates().get(1).interpretedDates())
            .containsExactly(LocalDate.of(2026, 3, 9), LocalDate.of(2026, 11, 9));
        assertThat(parsed.warnings()).anyMatch(warning -> warning.contains("confirm a format"));
    }

    @Test
    @DisplayName("an unpadded export (3/9/2026) is offered as a choice, not read as null")
    void offersCandidatesForAnUnpaddedFile() {
        ParsedUpload parsed = read("""
            Date,Description,Amount
            3/9/2026,A,-1.00
            1/2/2026,B,-2.00
            """);

        assertThat(parsed.dateFormatCandidates()).extracting("format")
            .as("neither padded pattern reads a single-digit cell, so before the unpadded pair "
                + "existed this file produced no candidates and no format at all")
            .containsExactly("d/M/uuuu", "M/d/uuuu");
        assertThat(parsed.rows()).hasSize(2).noneMatch(ParsedRow::hasError);
        assertThat(parsed.rows().get(0).row().date()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(parsed.warnings()).anyMatch(warning -> warning.contains("confirm a format"));
    }

    @Test
    @DisplayName("one unambiguous unpadded row settles an unpadded file the same way")
    void oneUnambiguousUnpaddedRowSettlesTheFormat() {
        ParsedUpload parsed = read("""
            Date,Description,Amount
            3/9/2026,A,-1.00
            13/9/2026,B,-2.00
            """);

        assertThat(parsed.mapping().dateFormat()).isEqualTo("d/M/uuuu");
        assertThat(parsed.dateFormatCandidates()).isEmpty();
        assertThat(parsed.rows()).noneMatch(ParsedRow::hasError);
        assertThat(parsed.rows().get(1).row().date()).isEqualTo(LocalDate.of(2026, 9, 13));
    }

    @Test
    @DisplayName("a date column nothing can read says so, instead of 'could not be read as null'")
    void anUnreadableDateColumnSaysSo() {
        ParsedUpload parsed = read("""
            Date,Description,Amount
            not-a-date,A,-1.00
            still-not,B,-2.00
            """);

        assertThat(parsed.mapping().dateFormat()).isNull();
        assertThat(parsed.dateFormatCandidates()).isEmpty();
        assertThat(parsed.warnings())
            .as("with no candidates to choose from, the only actionable thing to say is that no "
                + "known format reads the column")
            .anyMatch(warning -> warning.contains("No date format this server knows"));
        assertThat(parsed.rows()).hasSize(2).allMatch(ParsedRow::hasError);
        assertThat(parsed.rows().get(0).error())
            .isEqualTo("no date format could be established for this file, so this row's date "
                + "could not be read")
            .doesNotContain("null");
    }

    @Test
    @DisplayName("one unambiguous row settles the format for the whole file")
    void oneUnambiguousRowSettlesTheFormat() {
        ParsedUpload parsed = read("""
            Date,Description,Amount
            03/09/2026,A,-1.00
            13/09/2026,B,-2.00
            """);

        assertThat(parsed.mapping().dateFormat()).isEqualTo("dd/MM/uuuu");
        assertThat(parsed.dateFormatCandidates()).isEmpty();
        assertThat(parsed.rows().get(0).row().date()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    @DisplayName("a balance column is recognised, warned about, and not imported")
    void recognisesButDoesNotImportABalance() {
        ParsedUpload parsed = read("""
            Date,Description,Amount,Running Balance
            2026-09-01,A,-1.00,500.00
            """);

        assertThat(parsed.mapping().balanceColumn()).isEqualTo("Running Balance");
        assertThat(parsed.mapping().amountColumn()).isEqualTo("Amount");
        assertThat(parsed.rows().get(0).row().amount()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(parsed.warnings()).anyMatch(warning -> warning.contains("balance"));
    }

    @Test
    @DisplayName("a currency column is used; without one the row leaves it to the account")
    void readsAnOptionalCurrencyColumn() {
        assertThat(read("Date,Description,Amount,Currency\n2026-09-01,A,-1.00,USD\n")
            .rows().get(0).row().currency()).isEqualTo("USD");
        assertThat(read("Date,Description,Amount\n2026-09-01,A,-1.00\n")
            .rows().get(0).row().currency())
            .as("null means the account's own — never a literal (finding F4)")
            .isNull();
    }

    @Test
    @DisplayName("a header nothing can be mapped from is refused, not half-imported")
    void refusesAnUnmappableHeader() {
        assertThatThrownBy(() -> read("Alpha,Beta,Gamma\n1,2,3\n"))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("transaction date");

        assertThatThrownBy(() -> read("Date,Notes\n2026-09-01,hello\n"))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("no amount column");
    }

    @Test
    @DisplayName("a row with no readable amount is kept with its reason, not dropped")
    void keepsAnUnreadableRowWithItsReason() {
        ParsedUpload parsed = read("""
            Date,Description,Amount
            2026-09-01,A,not-a-number
            2026-09-02,B,-1.00
            """);

        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).error()).contains("no amount");
        assertThat(parsed.rows().get(0).row().amount())
            .as("the pipeline then refuses it with a reason a member can see, and it is counted")
            .isNull();
    }

    @Test
    @DisplayName("a field over the per-field ceiling is refused without quoting it")
    void refusesAnOverlongField() {
        CsvSource strict = new CsvSource(new CsvHeaderSniffer(), null,
            new ImportLimits(1024, 100, 8, java.time.Duration.ofSeconds(5), 5, 3, 0, null), null);

        ParsedUpload parsed = strict.read(
            "Date,Description,Amount\n2026-09-01,ABCDEFGHIJKLMNOP,-1.00\n"
                .getBytes(StandardCharsets.UTF_8), null, new ImportContext(ACCOUNT));

        assertThat(parsed.rows().get(0).error())
            .contains("description")
            .doesNotContain("ABCDEFGHIJKLMNOP");
    }

    @Test
    @DisplayName("more rows than the cap allows is refused before any of them is stored")
    void enforcesTheRowCap() {
        CsvSource strict = new CsvSource(new CsvHeaderSniffer(), null,
            new ImportLimits(1024, 1, 100, java.time.Duration.ofSeconds(5), 5, 3, 0, null), null);

        assertThatThrownBy(() -> strict.read(
            "Date,Description,Amount\n2026-09-01,A,-1.00\n2026-09-02,B,-2.00\n"
                .getBytes(StandardCharsets.UTF_8), null, new ImportContext(ACCOUNT)))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("at most 1");
    }

    @Test
    @DisplayName("B1-01: a decimal-comma export reads at its face value, not a hundred times it")
    void readsADecimalCommaExport() {
        ParsedUpload parsed = fixture("decimal-comma.csv");

        assertThat(parsed.rows()).hasSize(4).noneMatch(ParsedRow::hasError);
        assertThat(amounts(parsed)).usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("4.50"), new BigDecimal("1234.56"),
                new BigDecimal("1234.56"), new BigDecimal("12.5"));
        assertThat(types(parsed)).containsExactly(TransactionType.DEBIT, TransactionType.DEBIT,
            TransactionType.CREDIT, TransactionType.DEBIT);
    }

    @Test
    @DisplayName("B1-01: DR is spending and CR is a credit; an exponent is a row with a reason")
    void readsACardExportWithSuffixes() {
        ParsedUpload parsed = fixture("card-cr-dr.csv");

        assertThat(parsed.rows().get(0).row().type()).isEqualTo(TransactionType.DEBIT);
        assertThat(parsed.rows().get(0).row().amount()).isEqualByComparingTo("45.00");
        assertThat(parsed.rows().get(1).row().type()).isEqualTo(TransactionType.CREDIT);
        assertThat(parsed.rows().get(2).hasError())
            .as("1.5e3 used to be read as 1.53 with the letter skipped")
            .isTrue();
        assertThat(parsed.rows().get(2).error()).contains("amount");
    }

    @Test
    @DisplayName("the deterministic id is stable across two parses of the same bytes")
    void theIdIsStableAcrossParses() {
        String file = """
            Date,Description,Amount
            2026-09-01,COFFEE SHOP,-4.25
            2026-09-02,COFFEE SHOP,-4.25
            """;

        List<String> first = ids(read(file));
        List<String> second = ids(read(file));

        assertThat(second)
            .as("this is what puts CSV on the exact-match dedup path: a re-imported file is "
                + "recognised, which the fuzzy pass could never do for an already-posted row")
            .isEqualTo(first);
        assertThat(first).doesNotContainNull();
    }

    @Test
    @DisplayName("two identical-looking rows at different ordinals are two transactions")
    void theOrdinalKeepsIdenticalRowsApart() {
        List<String> ids = ids(read("""
            Date,Description,Amount
            2026-09-01,COFFEE SHOP,-4.25
            2026-09-01,COFFEE SHOP,-4.25
            """));

        assertThat(ids.get(0))
            .as("two coffees of the same amount on the same day are two purchases; the ±2 day, "
                + "±$0.50 fuzzy window would have collapsed them into one")
            .isNotEqualTo(ids.get(1));
    }

    @Test
    @DisplayName("B1-02: a wider export that overlaps an imported one gives the overlap the same ids")
    void anOverlappingExportKeepsItsIds() {
        List<String> january = ids(fixture("overlap-jan.csv"));
        List<String> januaryAndFebruary = ids(fixture("overlap-jan-feb.csv"));

        assertThat(januaryAndFebruary.subList(1, 3))
            .as("COFFEE and RENT moved down one line; the row ordinal used to be in the hash, so "
                + "they got new ids and the exact-match pass inserted them a second time")
            .isEqualTo(january);
        assertThat(januaryAndFebruary).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the id depends on the account, so the same file on two accounts is two ledgers")
    void theIdDependsOnTheAccount() {
        String file = "Date,Description,Amount\n2026-09-01,A,-1.00\n";
        UUID other = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

        String mine = ids(read(file)).get(0);
        String theirs = ids(source.read(file.getBytes(StandardCharsets.UTF_8), null,
            new ImportContext(other))).get(0);

        assertThat(mine).isNotEqualTo(theirs);
    }

    @Test
    @DisplayName("a supplied mapping supersedes the sniffed one, which is what commit does")
    void aSuppliedMappingSupersedes() {
        CsvColumnMapping chosen = new CsvColumnMapping("Date", "Description", "Amount", null, null,
            null, null, "MM/dd/uuuu", ",", "UTF-8", true);

        ParsedUpload parsed = source.read(
            "Date,Description,Amount\n03/09/2026,A,-1.00\n".getBytes(StandardCharsets.UTF_8),
            new ImportMapping("uploads/x/y.csv", "a.csv", SourceKind.CSV, chosen),
            new ImportContext(ACCOUNT));

        assertThat(parsed.rows().get(0).row().date()).isEqualTo(LocalDate.of(2026, 3, 9));
    }

    @Test
    @DisplayName("a CSV upload with no account named is refused — the file does not say")
    void refusesAnUnnamedAccount() {
        assertThatThrownBy(() -> source.read(
            "Date,Description,Amount\n2026-09-01,A,-1.00\n".getBytes(StandardCharsets.UTF_8),
            null, new ImportContext(null)))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("does not say which account");
    }

    @Test
    @DisplayName("a member who says the file has no header addresses its columns by ordinal")
    void aHeaderlessFileIsAddressedByOrdinal() {
        CsvColumnMapping byOrdinal = new CsvColumnMapping("0", "1", "2", null, null, null, null,
            "uuuu-MM-dd", ",", "UTF-8", false);

        ParsedUpload parsed = source.read(
            "2026-09-01,COFFEE,-4.25\n2026-09-02,LUNCH,-9.50\n"
                .getBytes(StandardCharsets.UTF_8),
            new ImportMapping("uploads/x/y.csv", "a.csv", SourceKind.CSV, byOrdinal),
            new ImportContext(ACCOUNT));

        assertThat(parsed.rows()).hasSize(2).noneMatch(ParsedRow::hasError);
        assertThat(parsed.rows().get(0).row().description()).isEqualTo("COFFEE");
        assertThat(parsed.rows().get(0).row().date()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("a mapped column that names nothing at all simply yields no value")
    void anUnresolvableColumnNameYieldsNothing() {
        CsvColumnMapping wrong = new CsvColumnMapping("Date", "Nowhere", "Amount", null, null,
            "AlsoNowhere", null, "uuuu-MM-dd", ",", "UTF-8", true);

        ParsedUpload parsed = source.read(
            "Date,Description,Amount\n2026-09-01,COFFEE,-4.25\n"
                .getBytes(StandardCharsets.UTF_8),
            new ImportMapping("uploads/x/y.csv", "a.csv", SourceKind.CSV, wrong),
            new ImportContext(ACCOUNT));

        assertThat(parsed.rows().get(0).hasError()).isFalse();
        assertThat(parsed.rows().get(0).row().description()).isNull();
        assertThat(parsed.rows().get(0).row().currency()).isNull();
        assertThat(parsed.rows().get(0).row().amount()).isEqualByComparingTo("4.25");
    }

    @Test
    @DisplayName("a header matched by punctuation alone still resolves to its column")
    void aColumnIsFoundByItsNormalisedName() {
        CsvColumnMapping punctuated = new CsvColumnMapping("transaction_date", "description",
            "amount", null, null, null, null, "uuuu-MM-dd", ",", "UTF-8", true);

        ParsedUpload parsed = source.read(
            "Transaction Date,Description,Amount\n2026-09-01,COFFEE,-4.25\n"
                .getBytes(StandardCharsets.UTF_8),
            new ImportMapping("uploads/x/y.csv", "a.csv", SourceKind.CSV, punctuated),
            new ImportContext(ACCOUNT));

        assertThat(parsed.rows().get(0).row().date()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    @DisplayName("an over-long cell is reported by the field it is in, whichever field that is")
    void namesTheOverlongField() {
        CsvSource strict = new CsvSource(new CsvHeaderSniffer(), null,
            new ImportLimits(1024, 100, 6, java.time.Duration.ofSeconds(5), 5, 3, 0, null), null);
        byte[] file = ("Date,Description,Amount,Currency\n"
            + "2026-09-01,A,-1.00,CANADIAN\n").getBytes(StandardCharsets.UTF_8);

        ParsedUpload parsed = strict.read(file, null, new ImportContext(ACCOUNT));

        assertThat(parsed.rows().get(0).error())
            .as("the date is over the ceiling too, but the description is checked first and is "
                + "the one a member would look at")
            .contains("date");
    }

    @Test
    @DisplayName("a file with nothing in it at all is refused")
    void refusesAnEmptyFile() {
        assertThatThrownBy(() -> read(""))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("no rows at all");
    }

    @Test
    @DisplayName("a header-only file yields a mapping and no rows")
    void aHeaderOnlyFileHasNoRows() {
        ParsedUpload parsed = read("Date,Description,Amount\n");

        assertThat(parsed.rows()).isEmpty();
        assertThat(parsed.mapping().dateColumn()).isEqualTo("Date");
        assertThat(parsed.mapping().dateFormat())
            .as("no data means no dates, so no format can be established")
            .isNull();
    }

    @Test
    @DisplayName("a row with no date is kept with its own reason")
    void keepsARowWithNoDate() {
        ParsedUpload parsed = read("Date,Description,Amount\n,A,-1.00\n");

        assertThat(parsed.rows().get(0).error()).contains("no date");
    }

    @Test
    @DisplayName("a debit/credit pair with neither cell populated is a row with no amount")
    void aPairWithNeitherCellIsAnEmptyRow() {
        ParsedUpload parsed = read("Date,Description,Debit,Credit\n2026-09-01,A,,\n");

        assertThat(parsed.rows().get(0).error()).contains("no amount");
    }

    @Test
    @DisplayName("the source serves CSV accounts")
    void servesCsv() {
        assertThat(source.sourceKind()).isEqualTo(SourceKind.CSV);
    }

    private ParsedUpload read(String content) {
        return source.read(content.getBytes(StandardCharsets.UTF_8), null,
            new ImportContext(ACCOUNT));
    }

    private ParsedUpload fixture(String name) {
        try (java.io.InputStream in = CsvSourceTest.class.getResourceAsStream("/csv/" + name)) {
            return source.read(java.util.Objects.requireNonNull(in, name).readAllBytes(), null,
                new ImportContext(ACCOUNT));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<String> ids(ParsedUpload parsed) {
        return parsed.rows().stream().map(row -> row.row().providerTransactionId()).toList();
    }

    private static List<BigDecimal> amounts(ParsedUpload parsed) {
        return parsed.rows().stream().map(row -> row.row().amount()).toList();
    }

    private static List<TransactionType> types(ParsedUpload parsed) {
        return parsed.rows().stream().map(row -> row.row().type()).toList();
    }
}
