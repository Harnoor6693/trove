package finance.trove.ingestion.simplefin;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.simplefin.dto.SimplefinAccount;
import finance.trove.ingestion.simplefin.dto.SimplefinTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The three defects of the pre-collapse SimpleFIN normaliser, each with the fix asserted. */
class SimplefinNormalizerTest {

    private static final SimplefinAccount ACCOUNT =
        new SimplefinAccount("acct-1", "Chequing", "CAD", null, List.of());

    private final SimplefinNormalizer normalizer = new SimplefinNormalizer();

    @Test
    @DisplayName("O7/M0: the identifier is the bridge's own id, never the literal \"simplefin\"")
    void theIdentifierIsTheBridgesOwn() {
        RawTransaction row = normalizer.normalize(ACCOUNT, transaction("tx-9",
            new BigDecimal("-12.34"), Instant.parse("2026-09-01T10:00:00Z"),
            Instant.parse("2026-09-02T00:00:00Z")));

        assertThat(row.providerTransactionId())
            .as("the source wrote a constant into this slot, so with the unique index on "
                + "(account_id, provider_transaction_id) an account could hold one transaction")
            .isEqualTo("tx-9")
            .isNotEqualTo("simplefin");
        assertThat(row.accountId()).isEqualTo("acct-1");
    }

    @Test
    @DisplayName("the amount is absolute and the sign becomes the direction")
    void directionComesFromTheSign() {
        assertThat(normalizer.normalize(ACCOUNT, transaction("a", new BigDecimal("-12.34"),
            Instant.parse("2026-09-01T10:00:00Z"), null)).type())
            .isEqualTo(TransactionType.DEBIT);
        assertThat(normalizer.normalize(ACCOUNT, transaction("b", new BigDecimal("12.34"),
            Instant.parse("2026-09-01T10:00:00Z"), null)).amount())
            .isEqualByComparingTo(new BigDecimal("12.34"));
    }

    @Test
    @DisplayName("B1-10: a credit the bank calls a refund is a REFUND, so it is not income")
    void aRefundIsNotIncome() {
        Instant day = Instant.parse("2026-09-01T10:00:00Z");

        assertThat(normalizer.normalize(ACCOUNT, new SimplefinTransaction("r",
            new BigDecimal("19.99"), "AMAZON.CA REFUND", day, day)).type())
            .isEqualTo(TransactionType.REFUND);
        assertThat(normalizer.normalize(ACCOUNT, new SimplefinTransaction("p",
            new BigDecimal("19.99"), "E-TRANSFER FROM SAM", day, day)).type())
            .isEqualTo(TransactionType.CREDIT);
    }

    @Test
    @DisplayName("a null posted time means pending")
    void pendingIsDerivedFromPosted() {
        assertThat(normalizer.normalize(ACCOUNT, transaction("a", BigDecimal.ONE,
            Instant.parse("2026-09-01T10:00:00Z"), null)).pending()).isTrue();
        assertThat(normalizer.normalize(ACCOUNT, transaction("a", BigDecimal.ONE,
            Instant.parse("2026-09-01T10:00:00Z"),
            Instant.parse("2026-09-02T00:00:00Z"))).pending()).isFalse();
    }

    @Test
    @DisplayName("a row with no date at all is left dateless rather than dated to today")
    void aDatelessRowIsNotDatedToToday() {
        RawTransaction row = normalizer.normalize(ACCOUNT,
            transaction("a", BigDecimal.ONE, null, null));

        assertThat(row.date())
            .as("the source fell back to LocalDate.now(UTC), which silently dated somebody else's "
                + "transaction to the day of the sync — and to a different day on the next one")
            .isNull();
    }

    @Test
    @DisplayName("posted stands in for transacted when only one of them is there")
    void postedStandsInForTransacted() {
        RawTransaction row = normalizer.normalize(ACCOUNT, transaction("a", BigDecimal.ONE, null,
            Instant.parse("2026-09-02T00:00:00Z")));

        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    @DisplayName("F4: currency is the account's, and a bridge that states none states none")
    void currencyComesFromTheAccount() {
        assertThat(normalizer.normalize(ACCOUNT, transaction("a", BigDecimal.ONE,
            Instant.parse("2026-09-01T10:00:00Z"), null)).currency()).isEqualTo("CAD");

        SimplefinAccount silent = new SimplefinAccount("acct-2", "X", "  ", null, List.of());
        assertThat(normalizer.normalize(silent, transaction("a", BigDecimal.ONE,
            Instant.parse("2026-09-01T10:00:00Z"), null)).currency())
            .as("null means the account's own, which is what the ingestion side reads it as")
            .isNull();
    }

    private static SimplefinTransaction transaction(String id, BigDecimal amount,
                                                    Instant transacted, Instant posted) {
        return new SimplefinTransaction(id, amount, "A DESCRIPTION", transacted, posted);
    }
}
