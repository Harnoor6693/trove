package finance.trove.budgets.envelope;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link EnvelopeHealthCalculator} — the month-shape and rounding edges.
 *
 * <p>These are the cases the pre-collapse calculator got wrong or never met: a period read after
 * its month is over, a month that is not 31 days, February in a leap year, and an amount whose
 * exact division does not land on a cent.
 */
class EnvelopeHealthCalculatorTest {

    private static final BigDecimal ALLOCATED = new BigDecimal("400.00");

    private static Envelope envelope(String allocated, String spent, String rollover,
                                     CurrencyCode currency) {
        Envelope envelope = new Envelope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            new BigDecimal(allocated), currency, Instant.parse("2026-01-01T00:00:00Z"));
        envelope.rewriteSpent(new BigDecimal(spent));
        envelope.setRolloverAmount(new BigDecimal(rollover));
        return envelope;
    }

    @Nested
    @DisplayName("the pace marker is about the period's month, not about today's day number")
    class PaceIsPerPeriodMonth {

        @Test
        @DisplayName("a month already over is 100 % elapsed, whatever today's date is")
        void aFinishedMonthIsFullyElapsed() {
            // The pre-collapse defect exactly: read on the 5th of October, the source reported a
            // September envelope as 5/30 elapsed and therefore comfortably under pace.
            EnvelopeHealth health = EnvelopeHealthCalculator.calculate(
                envelope("400.00", "400.00", "0.00", CurrencyCode.CAD),
                YearMonth.of(2026, 9), LocalDate.of(2026, 10, 5));

            assertThat(health.paceMarkerPercentage())
                .as("September is over on the 5th of October, however few days October has had")
                .isEqualTo(100);
            assertThat(health.expectedSpend())
                .as("a finished month expected the whole allocation")
                .isEqualByComparingTo(ALLOCATED);
            assertThat(health.overPace()).isFalse();
        }

        @Test
        @DisplayName("a month that has not started is 0 % elapsed and expects nothing")
        void aFutureMonthHasNotStarted() {
            EnvelopeHealth health = EnvelopeHealthCalculator.calculate(
                envelope("400.00", "0.00", "0.00", CurrencyCode.CAD),
                YearMonth.of(2026, 11), LocalDate.of(2026, 10, 5));

            assertThat(health.paceMarkerPercentage()).isZero();
            assertThat(health.expectedSpend()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(health.remainingAmount()).isEqualByComparingTo(ALLOCATED);
        }

        @Test
        @DisplayName("a 30-day month: the 15th is half of it, not 15/31")
        void aMonthThatEndsOnTheThirtieth() {
            EnvelopeHealth health = EnvelopeHealthCalculator.calculate(
                envelope("300.00", "0.00", "0.00", CurrencyCode.CAD),
                YearMonth.of(2026, 9), LocalDate.of(2026, 9, 15));

            assertThat(health.paceMarkerPercentage())
                .as("15 of 30 days, half-up")
                .isEqualTo(50);
            assertThat(health.expectedSpend()).isEqualByComparingTo(new BigDecimal("150.00"));
        }

        @Test
        @DisplayName("February 29 in a leap year is the last day of a 29-day month")
        void februaryTwentyNineInALeapYear() {
            EnvelopeHealth health = EnvelopeHealthCalculator.calculate(
                envelope("290.00", "0.00", "0.00", CurrencyCode.CAD),
                YearMonth.of(2028, 2), LocalDate.of(2028, 2, 29));

            assertThat(YearMonth.of(2028, 2).lengthOfMonth())
                .as("2028 is a leap year; if this ever reads 28 the test below means nothing")
                .isEqualTo(29);
            assertThat(health.paceMarkerPercentage()).isEqualTo(100);
            assertThat(health.expectedSpend()).isEqualByComparingTo(new BigDecimal("290.00"));
        }

        @Test
        @DisplayName("a January 31 period read on February 1 is finished, not 1/31 elapsed")
        void januaryThirtyFirstRollsIntoFebruary() {
            EnvelopeHealth health = EnvelopeHealthCalculator.calculate(
                envelope("310.00", "155.00", "0.00", CurrencyCode.CAD),
                YearMonth.of(2026, 1), LocalDate.of(2026, 2, 1));

            assertThat(health.paceMarkerPercentage()).isEqualTo(100);
            assertThat(health.healthPercentage())
                .as("half the allocation spent")
                .isEqualTo(50);
            assertThat(health.overPace())
                .as("half spent against a fully elapsed month is under pace, not over")
                .isFalse();
        }
    }

    @Nested
    @DisplayName("the amounts round to the currency's own scale")
    class MoneyEdges {

        @Test
        @DisplayName("an expected spend that does not divide evenly is rounded, not truncated")
        void expectedSpendRoundsHalfUp() {
            // 100.00 * 10 / 31 = 32.258064…, which is 32.26 at two decimal places.
            EnvelopeHealth health = EnvelopeHealthCalculator.calculate(
                envelope("100.00", "0.00", "0.00", CurrencyCode.CAD),
                YearMonth.of(2026, 1), LocalDate.of(2026, 1, 10));

            assertThat(health.expectedSpend()).isEqualByComparingTo(new BigDecimal("32.26"));
            assertThat(health.expectedSpend().scale())
                .as("quoted at the currency's minor units, never at the raw division's precision")
                .isEqualTo(CurrencyCode.CAD.minorUnits());
        }

        @Test
        @DisplayName("the remaining amount includes the carry and is floored at zero")
        void remainingIncludesTheCarryAndNeverGoesNegative() {
            EnvelopeHealth withCarry = EnvelopeHealthCalculator.calculate(
                envelope("100.00", "40.00", "25.00", CurrencyCode.CAD),
                YearMonth.of(2026, 1), LocalDate.of(2026, 1, 10));
            assertThat(withCarry.remainingAmount())
                .as("100 allocated + 25 carried - 40 spent")
                .isEqualByComparingTo(new BigDecimal("85.00"));

            EnvelopeHealth overspent = EnvelopeHealthCalculator.calculate(
                envelope("100.00", "175.00", "0.00", CurrencyCode.CAD),
                YearMonth.of(2026, 1), LocalDate.of(2026, 1, 10));
            assertThat(overspent.remainingAmount())
                .as("an overspent envelope reports nothing left, never a negative balance")
                .isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(overspent.healthPercentage())
                .as("health passes 100 to say so, which is where the remaining figure cannot")
                .isEqualTo(175);
        }

        @Test
        @DisplayName("an envelope with nothing allocated has no ratio and is never over pace")
        void nothingAllocatedShortCircuits() {
            EnvelopeHealth health = EnvelopeHealthCalculator.calculate(
                envelope("0.00", "50.00", "0.00", CurrencyCode.USD),
                YearMonth.of(2026, 1), LocalDate.of(2026, 1, 16));

            assertThat(health.healthPercentage()).isZero();
            assertThat(health.overPace())
                .as("there is no pace to exceed when nothing was budgeted")
                .isFalse();
            assertThat(health.paceMarkerPercentage())
                .as("the month still elapses, and the client still draws the marker")
                .isEqualTo(52);
        }

        @Test
        @DisplayName("over pace only past the 10 % buffer")
        void overPaceHasATenPercentBuffer() {
            // Half of a 31-day January is 15.5 days; on the 16th, 100.00 expects 51.61.
            EnvelopeHealth justUnder = EnvelopeHealthCalculator.calculate(
                envelope("100.00", "56.00", "0.00", CurrencyCode.CAD),
                YearMonth.of(2026, 1), LocalDate.of(2026, 1, 16));
            assertThat(justUnder.overPace())
                .as("56.00 against a 51.61 expectation is inside the 110 % buffer (56.77)")
                .isFalse();

            EnvelopeHealth justOver = EnvelopeHealthCalculator.calculate(
                envelope("100.00", "57.00", "0.00", CurrencyCode.CAD),
                YearMonth.of(2026, 1), LocalDate.of(2026, 1, 16));
            assertThat(justOver.overPace()).isTrue();
        }
    }
}
