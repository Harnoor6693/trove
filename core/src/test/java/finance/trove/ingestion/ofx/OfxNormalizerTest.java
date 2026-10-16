package finance.trove.ingestion.ofx;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.accounts.AccountType;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.ofx.dto.OfxAccount;
import finance.trove.ingestion.ofx.dto.OfxTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The mapping from OFX tags onto the ledger's own vocabulary, tag by tag. */
class OfxNormalizerTest {

    private static final OfxAccount ACCOUNT = new OfxAccount("ACC1", "0001", "CHECKING", "CAD");

    private final OfxNormalizer normalizer = new OfxNormalizer();

    @Test
    @DisplayName("TRNTYPE refines the direction where OFX is unambiguous, and the sign decides "
        + "where it is not")
    void mapsTheDirection() {
        assertThat(type("FEE", "-3.00")).isEqualTo(TransactionType.FEE);
        assertThat(type("SRVCHG", "-3.00")).isEqualTo(TransactionType.FEE);
        assertThat(type("INT", "1.20")).isEqualTo(TransactionType.INTEREST);
        assertThat(type("XFER", "-50.00"))
            .as("B1-11: a transfer keeps its direction, so pairing finds both legs; TRANSFER "
                + "rows are never paired, and an outgoing one looked like an incoming one")
            .isEqualTo(TransactionType.DEBIT);
        assertThat(type("XFER", "50.00")).isEqualTo(TransactionType.CREDIT);
        assertThat(type("FEE", "5.00"))
            .as("B1-11: a fee reversal is money coming back, not a fee")
            .isEqualTo(TransactionType.CREDIT);
        assertThat(type("INT", "-5.00"))
            .as("B1-11: interest charged is money going out, not interest earned")
            .isEqualTo(TransactionType.DEBIT);
        assertThat(type("DEBIT", "-1.00")).isEqualTo(TransactionType.DEBIT);
        assertThat(type("CREDIT", "1.00")).isEqualTo(TransactionType.CREDIT);
        assertThat(type("POS", "-1.00")).isEqualTo(TransactionType.DEBIT);
        assertThat(type(null, "1.00")).isEqualTo(TransactionType.CREDIT);
        assertThat(type("  fee  ", "-1.00"))
            .as("TRNTYPE is matched case-insensitively and trimmed, as banks write it")
            .isEqualTo(TransactionType.FEE);
    }

    @Test
    @DisplayName("B1-10: a credit whose NAME or MEMO says refund is a REFUND, not income")
    void aRefundIsNotIncome() {
        assertThat(normalizer.normalize(ACCOUNT, new OfxTransaction("F1", "CREDIT",
            LocalDate.of(2026, 9, 2), null, new BigDecimal("40.00"), "AMAZON.CA", "Refund",
            null, null)).type()).isEqualTo(TransactionType.REFUND);
        assertThat(normalizer.normalize(ACCOUNT, new OfxTransaction("F2", "CREDIT",
            LocalDate.of(2026, 9, 2), null, new BigDecimal("40.00"), "AMAZON.CA", null,
            null, null)).type()).isEqualTo(TransactionType.CREDIT);
    }

    @Test
    @DisplayName("F4: CURSYM, then CURDEF, then nothing — never a literal")
    void currencyPrecedence() {
        assertThat(normalise(transaction("FEE", "-1.00", "USD")).currency()).isEqualTo("USD");
        assertThat(normalise(transaction("FEE", "-1.00", "   ")).currency()).isEqualTo("CAD");
        assertThat(normalise(transaction("FEE", "-1.00", null)).currency()).isEqualTo("CAD");

        OfxAccount silent = new OfxAccount("ACC1", "0001", "CHECKING", null);
        assertThat(normalizer.normalize(silent, transaction("FEE", "-1.00", null)).currency())
            .as("null means the account's own, which is what the ingestion side reads it as")
            .isNull();
        assertThat(normalizer.normalize(new OfxAccount("ACC1", "", "CHECKING", "  "),
            transaction("FEE", "-1.00", null)).currency()).isNull();
    }

    @Test
    @DisplayName("the amount is absolute and DTUSER wins over DTPOSTED")
    void amountAndDates() {
        OfxTransaction posted = new OfxTransaction("F1", "DEBIT", LocalDate.of(2026, 9, 2),
            LocalDate.of(2026, 9, 1), new BigDecimal("-12.34"), "SHOP", "Memo", "42", null);

        RawTransaction row = normalizer.normalize(ACCOUNT, posted);

        assertThat(row.amount()).isEqualByComparingTo("12.34");
        assertThat(row.date())
            .as("OFX semantics: the user-visible date takes precedence over the settlement date")
            .isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(row.postedAt()).isEqualTo(Instant.parse("2026-09-02T00:00:00Z"));
        assertThat(row.pending()).isFalse();
        assertThat(row.metadata()).containsEntry("ofxType", "DEBIT").containsEntry("checkNum", "42");
    }

    @Test
    @DisplayName("no DTPOSTED means no postedAt, and DTUSER alone still dates the row")
    void aRowWithOnlyADtuser() {
        RawTransaction row = normalizer.normalize(ACCOUNT, new OfxTransaction("F1", null, null,
            LocalDate.of(2026, 9, 1), new BigDecimal("1.00"), null, null, null, null));

        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(row.postedAt()).isNull();
        assertThat(row.metadata()).containsEntry("ofxType", "").containsEntry("checkNum", "");
    }

    @Test
    @DisplayName("MEMO is the description, NAME the merchant, and NAME stands in when MEMO is absent")
    void descriptionFallsBackToName() {
        assertThat(normalizer.normalize(ACCOUNT, new OfxTransaction("F1", "DEBIT",
            LocalDate.of(2026, 9, 2), null, new BigDecimal("-1.00"), "SHOP", "A memo", null, null))
            .description()).isEqualTo("A memo");
        RawTransaction noMemo = normalizer.normalize(ACCOUNT, new OfxTransaction("F1", "DEBIT",
            LocalDate.of(2026, 9, 2), null, new BigDecimal("-1.00"), "SHOP", null, null, null));
        assertThat(noMemo.description()).isEqualTo("SHOP");
        assertThat(noMemo.merchantName()).isEqualTo("SHOP");
    }

    @Test
    @DisplayName("ACCTTYPE and CURDEF map onto the ledger's own closed sets, or onto nothing")
    void accountMetadataMapping() {
        assertThat(OfxSource.accountType("CHECKING")).isEqualTo(AccountType.CHECKING);
        assertThat(OfxSource.accountType("SAVINGS")).isEqualTo(AccountType.SAVINGS);
        assertThat(OfxSource.accountType("MONEYMRKT")).isEqualTo(AccountType.SAVINGS);
        assertThat(OfxSource.accountType("CREDITCARD")).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(OfxSource.accountType("creditline")).isEqualTo(AccountType.CREDIT_CARD);
        assertThat(OfxSource.accountType("SOMETHING ELSE")).isEqualTo(AccountType.OTHER);
        assertThat(OfxSource.accountType(null)).isEqualTo(AccountType.OTHER);

        assertThat(OfxSource.currency("CAD")).isEqualTo(CurrencyCode.CAD);
        assertThat(OfxSource.currency(" usd ")).isEqualTo(CurrencyCode.USD);
        assertThat(OfxSource.currency("GBP"))
            .as("a currency this install does not support is not a reason to refuse the file; "
                + "the account takes the member's own and each row is judged on its own code")
            .isNull();
        assertThat(OfxSource.currency(null)).isNull();
        assertThat(OfxSource.currency("   ")).isNull();
    }

    private TransactionType type(String trnType, String amount) {
        return normalise(transaction(trnType, amount, null)).type();
    }

    private RawTransaction normalise(OfxTransaction transaction) {
        return normalizer.normalize(ACCOUNT, transaction);
    }

    private static OfxTransaction transaction(String trnType, String amount, String currency) {
        return new OfxTransaction("F1", trnType, LocalDate.of(2026, 9, 2), null,
            new BigDecimal(amount), "SHOP", "Memo", null, currency);
    }
}
