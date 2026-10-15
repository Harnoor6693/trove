package finance.trove.ingestion.importbatch.dto;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ingestion.CsvColumnMapping;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The member's adjustment, applied over what the preview detected — the reason there is no fifth
 * route.
 */
class CsvColumnMappingRequestTest {

    private static final CsvColumnMapping DETECTED = new CsvColumnMapping("Date", "Description",
        "Amount", null, null, null, "Balance", "uuuu-MM-dd", ",", "UTF-8", true);

    @Test
    @DisplayName("an empty adjustment changes nothing at all")
    void anEmptyAdjustmentIsANoOp() {
        CsvColumnMapping result = new CsvColumnMappingRequest(null, null, null, null, null, null,
            null, null, null).applyTo(DETECTED);

        assertThat(result).isEqualTo(DETECTED);
    }

    @Test
    @DisplayName("every field the member states replaces the detected one")
    void everyStatedFieldReplaces() {
        CsvColumnMapping result = new CsvColumnMappingRequest("D", "Memo", null, "Out", "In",
            "Ccy", "Bal", "dd/MM/uuuu", false).applyTo(DETECTED);

        assertThat(result.dateColumn()).isEqualTo("D");
        assertThat(result.descriptionColumn()).isEqualTo("Memo");
        assertThat(result.amountColumn())
            .as("naming the debit/credit pair says the amount arrives in two columns, so the "
                + "detected single column is cleared rather than left to win at read time")
            .isNull();
        assertThat(result.debitColumn()).isEqualTo("Out");
        assertThat(result.creditColumn()).isEqualTo("In");
        assertThat(result.currencyColumn()).isEqualTo("Ccy");
        assertThat(result.balanceColumn()).isEqualTo("Bal");
        assertThat(result.dateFormat()).isEqualTo("dd/MM/uuuu");
        assertThat(result.hasHeader()).isFalse();
    }

    @Test
    @DisplayName("the delimiter and the charset are facts about the bytes and are never restated")
    void theBytesFactsSurvive() {
        CsvColumnMapping result =
            new CsvColumnMappingRequest(null, null, "A", null, null, null, null, null, null)
                .applyTo(new CsvColumnMapping("Date", null, "Amount", null, null, null, null, null,
                    ";", "windows-1252", true));

        assertThat(result.delimiter()).isEqualTo(";");
        assertThat(result.charset()).isEqualTo("windows-1252");
        assertThat(result.amountColumn()).isEqualTo("A");
    }

    @Test
    @DisplayName("correcting amountColumn clears a wrongly detected debit/credit pair")
    void statingTheAmountColumnClearsThePair() {
        CsvColumnMapping detectedAsPair = new CsvColumnMapping("Date", "Description", null,
            "Withdrawal", "Deposit", null, null, "uuuu-MM-dd", ",", "UTF-8", true);

        CsvColumnMapping result = new CsvColumnMappingRequest(null, null, "Amount", null, null,
            null, null, null, null).applyTo(detectedAsPair);

        assertThat(result.amountColumn()).isEqualTo("Amount");
        assertThat(result.debitColumn())
            .as("CsvSource prefers a resolved debit/credit pair over amountColumn, so leaving the "
                + "stale pair in place makes the member's correction a silent no-op and commits "
                + "rows read from the wrong columns")
            .isNull();
        assertThat(result.creditColumn()).isNull();
        assertThat(result.hasDebitCreditPair()).isFalse();
    }

    @Test
    @DisplayName("naming one half of the pair is enough to switch shape")
    void statingOneHalfOfThePairClearsTheAmountColumn() {
        CsvColumnMapping result = new CsvColumnMappingRequest(null, null, null, "Withdrawal", null,
            null, null, null, null).applyTo(DETECTED);

        assertThat(result.amountColumn()).isNull();
        assertThat(result.debitColumn()).isEqualTo("Withdrawal");
        assertThat(result.creditColumn()).isNull();
        assertThat(result.hasDebitCreditPair()).isTrue();
    }

    @Test
    @DisplayName("an empty string clears the field it is sent for without switching shape")
    void anEmptyStringClearsWithoutSwitching() {
        CsvColumnMapping result = new CsvColumnMappingRequest(null, null, "", null, null, "",
            null, null, null).applyTo(DETECTED);

        assertThat(result.amountColumn()).isEmpty();
        assertThat(result.currencyColumn()).isEmpty();
        assertThat(result.debitColumn())
            .as("a blank is not naming a column, so it cannot mean 'the amount is a single "
                + "column' either")
            .isNull();
    }

    @Test
    @DisplayName("stating both shapes at once lets the pair win, as detection itself would")
    void statingBothShapesPrefersThePair() {
        CsvColumnMapping result = new CsvColumnMappingRequest(null, null, "Amount", "Withdrawal",
            "Deposit", null, null, null, null).applyTo(DETECTED);

        assertThat(result.debitColumn()).isEqualTo("Withdrawal");
        assertThat(result.creditColumn()).isEqualTo("Deposit");
        assertThat(result.amountColumn()).isNull();
    }

    @Test
    @DisplayName("withDateFormat and hasDebitCreditPair say what they mean")
    void mappingHelpers() {
        assertThat(DETECTED.withDateFormat("MM/dd/uuuu").dateFormat()).isEqualTo("MM/dd/uuuu");
        assertThat(DETECTED.withDateFormat(null).dateFormat()).isNull();
        assertThat(DETECTED.hasDebitCreditPair()).isFalse();
        assertThat(new CsvColumnMapping("Date", null, null, "Out", "In", null, null, null, ",",
            "UTF-8", true).hasDebitCreditPair()).isTrue();
        assertThat(new CsvColumnMapping("Date", null, null, null, null, null, null, null, ",",
            "UTF-8", true).hasDebitCreditPair())
            .as("no amount column and no pair either is not a pair")
            .isFalse();
    }
}
