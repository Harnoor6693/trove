package finance.trove.notifications.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** One currency's line of the digest. */
class WeeklyDigestSummaryTest {

    @Test
    @DisplayName("amounts are normalised to the currency's minor units")
    void amountsAreNormalised() {
        WeeklyDigestSummary summary = new WeeklyDigestSummary(CurrencyCode.CAD,
            new BigDecimal("10.005"), new BigDecimal("3"), 2, null, null);

        assertThat(summary.spent().scale()).isEqualTo(2);
        assertThat(summary.income()).isEqualByComparingTo(new BigDecimal("3.00"));
        assertThat(summary.topCategorySpend()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("net is income less spending, and may be negative")
    void netMayBeNegative() {
        WeeklyDigestSummary summary = new WeeklyDigestSummary(CurrencyCode.USD,
            new BigDecimal("120.00"), new BigDecimal("100.00"), 4, null, null);

        assertThat(summary.net()).isEqualByComparingTo(new BigDecimal("-20.00"));
        assertThat(summary.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("a currency is required — there is no default and no conversion")
    void aCurrencyIsRequired() {
        assertThatThrownBy(() -> new WeeklyDigestSummary(null, BigDecimal.ZERO, BigDecimal.ZERO,
            0, null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new WeeklyDigestSummary(CurrencyCode.CAD, BigDecimal.ZERO,
            BigDecimal.ZERO, -1, UUID.randomUUID(), null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
