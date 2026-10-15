package finance.trove.common.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link Money} is the type every balance in the ledger is expressed in, so these tests are
 * about the two ways a finance system loses money: rounding it into existence, and mixing
 * currencies without noticing.
 */
class MoneyTest {

    @Nested
    @DisplayName("construction normalises to the currency's minor-unit scale")
    class Construction {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "12.3,       12.30",
            "12,         12.00",
            "12.345,     12.35",
            "12.344,     12.34",
            "-12.345,   -12.35",
            "0.005,       0.01",
            "1E+2,      100.00",
        })
        void roundsHalfUpToTwoPlaces(String input, String expected) {
            assertThat(Money.of(input, CurrencyCode.CAD).amount())
                .isEqualByComparingTo(new BigDecimal(expected))
                .hasToString(expected);
        }

        @Test
        @DisplayName("a scientific-notation input still stores a plain decimal")
        void scientificNotationIsNormalised() {
            assertThat(new Money(new BigDecimal("1E+2"), CurrencyCode.USD).amount().toPlainString())
                .isEqualTo("100.00");
        }

        @Test
        void rejectsNulls() {
            assertThatThrownBy(() -> new Money(null, CurrencyCode.CAD))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("amount");
            assertThatThrownBy(() -> new Money(BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("currency");
        }

        @Test
        void factoriesAgree() {
            assertThat(Money.of(12L, CurrencyCode.CAD)).isEqualTo(Money.of("12.00", CurrencyCode.CAD));
            assertThat(Money.ofMinorUnits(1234L, CurrencyCode.USD))
                .isEqualTo(Money.of("12.34", CurrencyCode.USD));
            assertThat(Money.zero(CurrencyCode.CAD).isZero()).isTrue();
            assertThat(Money.of("12.34", CurrencyCode.USD).toMinorUnits()).isEqualTo(1234L);
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("0.1 + 0.2 is exactly 0.30, which is the whole reason this is not a double")
        void addsExactly() {
            Money sum = Money.of("0.10", CurrencyCode.CAD).add(Money.of("0.20", CurrencyCode.CAD));
            assertThat(sum.amount().toPlainString()).isEqualTo("0.30");
        }

        @Test
        void subtractsNegatesAndAbsolutes() {
            Money ten = Money.of("10.00", CurrencyCode.CAD);
            Money three = Money.of("3.50", CurrencyCode.CAD);
            assertThat(ten.subtract(three)).isEqualTo(Money.of("6.50", CurrencyCode.CAD));
            assertThat(three.subtract(ten).isNegative()).isTrue();
            assertThat(three.subtract(ten).negate()).isEqualTo(Money.of("6.50", CurrencyCode.CAD));
            assertThat(three.subtract(ten).abs()).isEqualTo(Money.of("6.50", CurrencyCode.CAD));
        }

        @Test
        void multipliesAndDividesWithHalfUpRounding() {
            Money amount = Money.of("10.00", CurrencyCode.CAD);
            assertThat(amount.multiply(new BigDecimal("0.135")))
                .isEqualTo(Money.of("1.35", CurrencyCode.CAD));
            assertThat(amount.divide(new BigDecimal("3")))
                .isEqualTo(Money.of("3.33", CurrencyCode.CAD));
        }

        @Test
        void refusesDivisionByZeroAndNulls() {
            Money amount = Money.of("10.00", CurrencyCode.CAD);
            assertThatThrownBy(() -> amount.divide(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero");
            assertThatThrownBy(() -> amount.divide(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> amount.multiply(null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("mixing CAD and USD throws rather than silently adding the numbers")
        void refusesCrossCurrencyArithmetic() {
            Money cad = Money.of("10.00", CurrencyCode.CAD);
            Money usd = Money.of("10.00", CurrencyCode.USD);
            assertThatThrownBy(() -> cad.add(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CAD")
                .hasMessageContaining("USD");
            assertThatThrownBy(() -> cad.subtract(usd)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cad.compareTo(usd)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> cad.add(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("comparison and identity")
    class Comparison {

        @Test
        void comparesWithinACurrency() {
            Money small = Money.of("1.00", CurrencyCode.CAD);
            Money large = Money.of("2.00", CurrencyCode.CAD);
            assertThat(small.isLessThan(large)).isTrue();
            assertThat(large.isGreaterThan(small)).isTrue();
            assertThat(small.isPositive()).isTrue();
            assertThat(small.compareTo(small)).isZero();
        }

        @Test
        @DisplayName("1.5 and 1.50 are the same Money because the scale is fixed at construction")
        void equalityIgnoresInputScale() {
            assertThat(Money.of("1.5", CurrencyCode.CAD)).isEqualTo(Money.of("1.50", CurrencyCode.CAD))
                .hasSameHashCodeAs(Money.of("1.50", CurrencyCode.CAD));
            assertThat(Money.of("1.50", CurrencyCode.CAD))
                .isNotEqualTo(Money.of("1.50", CurrencyCode.USD))
                .isNotEqualTo("1.50 CAD")
                .isEqualTo(Money.of("1.50", CurrencyCode.CAD));
        }

        @Test
        void toStringIsPlainNotation() {
            assertThat(new Money(new BigDecimal("1E+2"), CurrencyCode.CAD))
                .hasToString("100.00 CAD");
        }
    }
}
