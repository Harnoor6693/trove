package finance.trove.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Money and dates as real bank exports write them, at the level below {@code CsvSource}. */
class CsvValuesTest {

    @Test
    @DisplayName("every negative and grouping convention a Canadian or American bank writes")
    void readsMoney() {
        assertThat(CsvValues.amount("1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(CsvValues.amount("(42.00)")).isEqualByComparingTo("-42.00");
        assertThat(CsvValues.amount("42.00-")).isEqualByComparingTo("-42.00");
        assertThat(CsvValues.amount("-42.00")).isEqualByComparingTo("-42.00");
        assertThat(CsvValues.amount("+42.00")).isEqualByComparingTo("42.00");
        assertThat(CsvValues.amount("$1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(CsvValues.amount("1 234.56")).isEqualByComparingTo("1234.56");
        assertThat(CsvValues.amount("1 234.56"))
            .as("a non-breaking space is what a spreadsheet writes between symbol and digits")
            .isEqualByComparingTo("1234.56");
        assertThat(CsvValues.amount("1'234.56")).isEqualByComparingTo("1234.56");
        assertThat(CsvValues.amount("12.34 CAD")).isEqualByComparingTo("12.34");
        assertThat(CsvValues.amount("(1,234.56)")).isEqualByComparingTo("-1234.56");
    }

    @Test
    @DisplayName("a cell that is not money at all is null, not a guess")
    void refusesWhatIsNotMoney() {
        assertThat(CsvValues.amount(null)).isNull();
        assertThat(CsvValues.amount("")).isNull();
        assertThat(CsvValues.amount("   ")).isNull();
        assertThat(CsvValues.amount("not-a-number")).isNull();
        assertThat(CsvValues.amount("12/34")).isNull();
        assertThat(CsvValues.amount("$")).isNull();
        assertThat(CsvValues.amount(".")).isNull();
        assertThat(CsvValues.amount("1.2.3")).isNull();
    }

    @Test
    @DisplayName("a date is read under the pattern it was given, and only under that one")
    void readsDates() {
        assertThat(CsvValues.date("2026-09-03", "uuuu-MM-dd")).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(CsvValues.date("Sep 3, 2026", "MMM d uuuu")).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(CsvValues.date("3 September 2026", "d MMMM uuuu"))
            .isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(CsvValues.date("20260903", "uuuuMMdd")).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(CsvValues.date("2026-09-03", "dd/MM/uuuu")).isNull();
        assertThat(CsvValues.date(null, "uuuu-MM-dd")).isNull();
        assertThat(CsvValues.date("2026-09-03", null)).isNull();
        assertThat(CsvValues.date("   ", "uuuu-MM-dd")).isNull();
        assertThat(CsvValues.date("31/02/2026", "dd/MM/uuuu"))
            .as("STRICT resolution: there is no 31st of February, and a lenient resolver would "
                + "smooth it into the 3rd of March")
            .isNull();
    }

    @Test
    @DisplayName("an unambiguous pattern is detected; an ambiguous file gets no answer")
    void detectsTheFormat() {
        assertThat(CsvValues.detectDateFormat(List.of("2026-09-03", "2026-09-11")))
            .isEqualTo("uuuu-MM-dd");
        assertThat(CsvValues.detectDateFormat(List.of("2026/09/03"))).isEqualTo("uuuu/MM/dd");
        assertThat(CsvValues.detectDateFormat(List.of("13/09/2026", "03/09/2026")))
            .isEqualTo("dd/MM/uuuu");
        assertThat(CsvValues.detectDateFormat(List.of("09/13/2026", "09/03/2026")))
            .isEqualTo("MM/dd/uuuu");
        assertThat(CsvValues.detectDateFormat(List.of("13-09-2026"))).isEqualTo("dd-MM-uuuu");
        assertThat(CsvValues.detectDateFormat(List.of("09-13-2026"))).isEqualTo("MM-dd-uuuu");
        assertThat(CsvValues.detectDateFormat(List.of("03/09/2026", "11/09/2026")))
            .as("both readings fit every cell, so the file cannot answer and the member must")
            .isNull();
        assertThat(CsvValues.detectDateFormat(List.of())).isNull();
        assertThat(CsvValues.detectDateFormat(List.of("not a date"))).isNull();
    }

    @Test
    @DisplayName("candidates are offered only when the file is genuinely ambiguous")
    void offersCandidatesOnlyWhenAmbiguous() {
        assertThat(CsvValues.dateFormatCandidates(List.of("2026-09-03"), 3)).isEmpty();
        assertThat(CsvValues.dateFormatCandidates(List.of("13/09/2026"), 3)).isEmpty();
        assertThat(CsvValues.dateFormatCandidates(List.of("not a date"), 3)).isEmpty();

        assertThat(CsvValues.dateFormatCandidates(
            List.of("03/09/2026", "03/09/2026", "11/09/2026"), 3))
            .as("the samples are DISTINCT values, so a file of one repeated date does not fill "
                + "the list with the same cell three times")
            .satisfies(candidates -> {
                assertThat(candidates).hasSize(2);
                assertThat(candidates.get(0).sampleValues())
                    .containsExactly("03/09/2026", "11/09/2026");
                assertThat(candidates.get(0).interpretedDates())
                    .containsExactly(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 11));
            });

        assertThat(CsvValues.dateFormatCandidates(List.of("03-09-2026", "11-09-2026"), 1))
            .extracting("format").containsExactly("dd-MM-uuuu", "MM-dd-uuuu");
        assertThat(CsvValues.dateFormatCandidates(List.of("03-09-2026"), 0).get(0).sampleValues())
            .as("a zero sample size still shows at least one, or the offer means nothing")
            .hasSize(1);
    }

    @Test
    @DisplayName("an unpadded date (3/9/2026) is ambiguous like a padded one, not unreadable")
    void readsUnpaddedDates() {
        List<String> unpadded = List.of("3/9/2026", "1/2/2026");

        assertThat(CsvValues.date("3/9/2026", "dd/MM/uuuu"))
            .as("this is the whole defect: neither padded pattern reads a single-digit cell")
            .isNull();
        assertThat(CsvValues.detectDateFormat(unpadded))
            .as("both unpadded readings fit, so the member still chooses")
            .isNull();
        assertThat(CsvValues.dateFormatCandidates(unpadded, 2))
            .extracting("format")
            .containsExactly("d/M/uuuu", "M/d/uuuu");
        assertThat(CsvValues.date("3/9/2026", "d/M/uuuu")).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(CsvValues.date("3/9/2026", "M/d/uuuu")).isEqualTo(LocalDate.of(2026, 3, 9));
    }

    @Test
    @DisplayName("one unambiguous unpadded cell settles the reading for the whole file")
    void anUnpaddedFileCanStillBeUnambiguous() {
        assertThat(CsvValues.detectDateFormat(List.of("3/9/2026", "13/9/2026")))
            .isEqualTo("d/M/uuuu");
        assertThat(CsvValues.detectDateFormat(List.of("3/9/2026", "9/13/2026")))
            .isEqualTo("M/d/uuuu");
        assertThat(CsvValues.detectDateFormat(List.of("3-9-2026", "13-9-2026")))
            .isEqualTo("d-M-uuuu");
        assertThat(CsvValues.dateFormatCandidates(List.of("3-9-2026", "1-2-2026"), 2))
            .extracting("format").containsExactly("d-M-uuuu", "M-d-uuuu");
    }

    @Test
    @DisplayName("a padded file is still described as padded — the unpadded pair is tried after")
    void thePaddedPairKeepsPrecedence() {
        assertThat(CsvValues.dateFormatCandidates(List.of("03/09/2026", "11/09/2026"), 2))
            .extracting("format")
            .as("d/M/uuuu reads a padded cell too, so promoting it would rename the format "
                + "offered for every ordinary file")
            .containsExactly("dd/MM/uuuu", "MM/dd/uuuu");
        assertThat(CsvValues.detectDateFormat(List.of("13/09/2026", "03/09/2026")))
            .isEqualTo("dd/MM/uuuu");
    }

    @Test
    @DisplayName("a column that is not dates at all offers no candidates and no format")
    void anUnreadableDateColumnIsNotAChoice() {
        List<String> notDates = List.of("hello", "world");

        assertThat(CsvValues.detectDateFormat(notDates)).isNull();
        assertThat(CsvValues.dateFormatCandidates(notDates, 2))
            .as("a file nothing reads is an unmappable column, not a choice between two readings")
            .isEmpty();
    }

    @Test
    @DisplayName("a big amount keeps every digit — it is a BigDecimal, not a double")
    void keepsPrecision() {
        assertThat(CsvValues.amount("9,007,199,254,740,993.01"))
            .isEqualByComparingTo(new BigDecimal("9007199254740993.01"));
    }
}
