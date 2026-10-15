package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How money survives the round trip through {@code NUMERIC(19,4)}
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risks 5 and 6).
 *
 * <p>Two scales are in play and neither is an accident. The COLUMN is {@code NUMERIC(19,4)}, wide
 * enough that any supported currency's amount is representable exactly. The VALUE is normalised to
 * the currency's own minor units — two for both CAD and USD — because a transaction is a number of
 * cents and a stored third decimal would be a figure no statement could ever be reconciled
 * against.
 *
 * <p>The consequence a reader has to know about: a value written at scale 2 reads back from
 * Postgres at scale 4, so {@code equals} between the two is {@code false} while
 * {@code compareTo} is {@code 0}. Everything in this step that compares money uses
 * {@code compareTo} — the transfer-pairing rule most of all, where {@code equals} would silently
 * pair nothing at all.
 */
class TransactionAmountRoundingTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 4);

    @Test
    @DisplayName("An amount is normalised to the currency's minor units, half-up")
    void amountsAreRoundedHalfUpToTheCurrencyScale() {
        assertThat(transaction("12.344", CurrencyCode.CAD).getAmount())
            .isEqualByComparingTo("12.34");
        assertThat(transaction("12.345", CurrencyCode.CAD).getAmount())
            .as("half-up, not half-even: a ledger that rounded bankers' way would disagree with "
                + "every statement it is reconciled against")
            .isEqualByComparingTo("12.35");
        assertThat(transaction("12.3", CurrencyCode.USD).getAmount())
            .isEqualByComparingTo("12.30");
    }

    @Test
    @DisplayName("The stored scale is the currency's, and it fits NUMERIC(19,4) exactly")
    void theStoredScaleFitsTheColumn() {
        BigDecimal stored = transaction("12.3", CurrencyCode.CAD).getAmount();

        assertThat(stored.scale())
            .as("CAD has two minor units, so the value carries two decimals")
            .isEqualTo(2);
        assertThat(stored.setScale(4).compareTo(stored))
            .as("widening to the column's scale changes nothing, which is what makes the round "
                + "trip exact")
            .isZero();
    }

    @Test
    @DisplayName("A value read back at scale 4 compares equal to the same value at scale 2")
    void scaleDoesNotChangeTheAmount() {
        BigDecimal asWritten = new BigDecimal("250.00");
        BigDecimal asReadBackFromPostgres = new BigDecimal("250.0000");

        assertThat(asWritten.equals(asReadBackFromPostgres))
            .as("this is why nothing in this step compares money with equals()")
            .isFalse();
        assertThat(asWritten).isEqualByComparingTo(asReadBackFromPostgres);
    }

    @Test
    @DisplayName("A large amount is representable without loss at NUMERIC(19,4)")
    void aLargeAmountIsExact() {
        BigDecimal big = new BigDecimal("999999999999999.99");

        assertThat(transaction(big.toPlainString(), CurrencyCode.CAD).getAmount())
            .as("fifteen integral digits plus four decimals is the whole of NUMERIC(19,4)")
            .isEqualByComparingTo(big);
    }

    private static Transaction transaction(String amount, CurrencyCode currency) {
        return new Transaction(HOUSEHOLD, UUID.randomUUID(), UUID.randomUUID(), null,
            new BigDecimal(amount), currency, TransactionType.DEBIT, null, null, DAY,
            Instant.now(), false, true, Instant.now());
    }
}
