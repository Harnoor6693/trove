package finance.trove.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ingestion.CsvColumnMapping;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Encoding, delimiter and the §8.2 synonym table, at the level below {@code CsvSource}. */
class CsvHeaderSnifferTest {

    private final CsvHeaderSniffer sniffer = new CsvHeaderSniffer();

    @Test
    @DisplayName("a windows-1252 export is decoded as windows-1252, not mangled as UTF-8")
    void fallsBackToWindows1252ForBytesThatAreNotUtf8() {
        byte[] latin = "Date,Description,Amount\n2026-09-01,CAFÉ,-4.25\n"
            .getBytes(Charset.forName("windows-1252"));

        CsvHeaderSniffer.Decoded decoded = sniffer.decode(latin);

        assertThat(decoded.charset()).isEqualTo("windows-1252");
        assertThat(decoded.text()).contains("CAFÉ");
    }

    @Test
    @DisplayName("valid UTF-8 stays UTF-8, and a byte-order mark is consumed")
    void keepsUtf8AndConsumesTheBom() {
        CsvHeaderSniffer.Decoded plain =
            sniffer.decode("Date,Amount\n".getBytes(StandardCharsets.UTF_8));
        assertThat(plain.charset()).isEqualTo("UTF-8");

        CsvHeaderSniffer.Decoded withBom =
            sniffer.decode("﻿Date,Amount\n".getBytes(StandardCharsets.UTF_8));
        assertThat(withBom.text()).startsWith("Date");
    }

    @Test
    @DisplayName("the delimiter is the separator that appears most often outside quotes")
    void detectsTheDelimiter() {
        assertThat(sniffer.detectDelimiter("a,b,c\n1,2,3\n")).isEqualTo(',');
        assertThat(sniffer.detectDelimiter("a;b;c\n")).isEqualTo(';');
        assertThat(sniffer.detectDelimiter("a\tb\tc\n")).isEqualTo('\t');
        assertThat(sniffer.detectDelimiter("a|b|c\n")).isEqualTo('|');
        assertThat(sniffer.detectDelimiter("\"a,b,c\";d\n"))
            .as("a delimiter inside quotes is data, not a separator")
            .isEqualTo(';');
    }

    @Test
    @DisplayName("header names are matched however a bank punctuates them")
    void normalisesHeaderNames() {
        assertThat(CsvHeaderSniffer.normalise("Transaction_Date")).isEqualTo("transaction date");
        assertThat(CsvHeaderSniffer.normalise("  TRANSACTION   DATE  "))
            .isEqualTo("transaction date");
        assertThat(CsvHeaderSniffer.normalise(null)).isEmpty();
    }

    @Test
    @DisplayName("a single signed amount column wins over a duplicated debit/credit pair")
    void aSignedAmountWinsOverAPair() {
        CsvColumnMapping mapping = sniffer.map(
            List.of("Date", "Description", "Debit", "Credit", "Amount"), "UTF-8", ',');

        assertThat(mapping.amountColumn()).isEqualTo("Amount");
        assertThat(mapping.debitColumn())
            .as("adding a signed total to a debit/credit pair would double every row")
            .isNull();
        assertThat(mapping.creditColumn()).isNull();
        assertThat(mapping.hasDebitCreditPair()).isFalse();
    }

    @Test
    @DisplayName("a balance column is never mistaken for the amount")
    void aBalanceIsNotAnAmount() {
        CsvColumnMapping mapping =
            sniffer.map(List.of("Posted Date", "Memo", "Amount", "Balance"), "UTF-8", ',');

        assertThat(mapping.balanceColumn()).isEqualTo("Balance");
        assertThat(mapping.amountColumn()).isEqualTo("Amount");
        assertThat(mapping.dateFormat())
            .as("the format comes from the DATA, never from the header")
            .isNull();
    }
}
