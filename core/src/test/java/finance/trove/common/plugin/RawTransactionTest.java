package finance.trove.common.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RawTransaction} — the record whose two identifier slots were finding O7/M0.
 */
class RawTransactionTest {

    @Test
    @DisplayName("There is exactly one identifier slot, and it is the dedup key")
    void thereIsOnlyOneIdentifier() {
        assertThat(RawTransaction.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .as("the pre-collapse record had BOTH an unread `id` and a `sourceId` that every "
                + "normaliser filled with the plugin's name, so an account could hold one "
                + "transaction for ever (O7/M0). A record with one identifier cannot be filled "
                + "in the wrong one")
            .containsExactly("providerTransactionId", "accountId", "amount", "currency",
                "description", "merchantName", "date", "postedAt", "type", "pending", "metadata");
    }

    @Test
    @DisplayName("A blank or missing provider id puts the row on the fuzzy path")
    void hasProviderIdIsAboutUsability() {
        assertThat(row("bank-1").hasProviderId()).isTrue();
        assertThat(row(null).hasProviderId()).isFalse();
        assertThat(row("   ").hasProviderId())
            .as("a blank string is not an identifier; treating it as one would key every row of "
                + "such a source on the same value, which is the O7 failure again")
            .isFalse();
    }

    @Test
    @DisplayName("Metadata is never null and is copied defensively")
    void metadataIsCopied() {
        Map<String, String> mutable = new HashMap<>(Map.of("fitId", "abc"));
        RawTransaction transaction = new RawTransaction("bank-1", "ext", BigDecimal.ONE, "CAD",
            null, null, LocalDate.of(2026, 9, 4), null, TransactionType.DEBIT, false, mutable);

        mutable.put("fitId", "changed");

        assertThat(transaction.metadata()).containsExactly(Map.entry("fitId", "abc"));
        assertThat(row("bank-1").metadata()).isEmpty();
    }

    @Test
    @DisplayName("toString names the row without quoting a household's spending history")
    void toStringIsRedacted() {
        RawTransaction transaction = new RawTransaction("bank-1", "ext", BigDecimal.ONE, "CAD",
            "SQ *THERAPIST OFFICE", "A Sensitive Merchant", LocalDate.of(2026, 9, 4), null,
            TransactionType.DEBIT, false, Map.of());

        assertThat(transaction.toString())
            .as("this object is logged on every per-row ingestion failure")
            .contains("bank-1")
            .doesNotContain("THERAPIST")
            .doesNotContain("A Sensitive Merchant");
    }

    @Test
    @DisplayName("Two amounts that differ only in scale are the same transaction")
    void equalityComparesAmountsByValue() {
        RawTransaction twoDecimals = amount(new BigDecimal("12.34"));
        RawTransaction fourDecimals = amount(new BigDecimal("12.3400"));

        assertThat(twoDecimals)
            .as("a source that quotes four decimals and one that quotes two are reporting the "
                + "same money; BigDecimal.equals would call them different")
            .isEqualTo(fourDecimals)
            .hasSameHashCodeAs(fourDecimals);
        assertThat(twoDecimals).isNotEqualTo(amount(new BigDecimal("12.35")));
    }

    private static RawTransaction amount(BigDecimal value) {
        return RawTransaction.of("bank-1", "ext", value, "CAD", "d", "m",
            LocalDate.of(2026, 9, 4), Instant.EPOCH, TransactionType.DEBIT, false);
    }

    private static RawTransaction row(String providerId) {
        return RawTransaction.of(providerId, "ext", BigDecimal.ONE, "CAD", null, null,
            LocalDate.of(2026, 9, 4), null, TransactionType.DEBIT, false);
    }
}
