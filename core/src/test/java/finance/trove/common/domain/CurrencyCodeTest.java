package finance.trove.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The currency enum is the one place a {@code "CAD"} or {@code "USD"} literal is allowed
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risk 5), so it also owns the rounding scale.
 */
class CurrencyCodeTest {

    @Test
    @DisplayName("exactly two currencies, both with two minor units (owner decision 3)")
    void bothSupportedCurrenciesUseCents() {
        assertThat(CurrencyCode.values()).containsExactly(CurrencyCode.CAD, CurrencyCode.USD);
        for (CurrencyCode currency : CurrencyCode.values()) {
            assertThat(currency.minorUnits()).isEqualTo(2);
        }
    }

    @Test
    void normaliseRoundsHalfUp() {
        assertThat(CurrencyCode.CAD.normalise(new BigDecimal("1.005")))
            .isEqualByComparingTo("1.01");
        assertThat(CurrencyCode.USD.normalise(new BigDecimal("1.0049")))
            .isEqualByComparingTo("1.00");
    }

    @Test
    void mapsBackToItsJurisdiction() {
        assertThat(CurrencyCode.CAD.defaultJurisdiction()).isEqualTo(Jurisdiction.CA);
        assertThat(CurrencyCode.USD.defaultJurisdiction()).isEqualTo(Jurisdiction.US);
    }
}
