package finance.trove.subscriptions.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The confidence score, as a table of the cases that decide whether a candidate survives.
 *
 * <p>The score is {@code min(charges/4, 1)} plus an amount bonus of up to 0.30 and a regularity
 * bonus of up to 0.20, clamped to 1. Every number below is {@link BigDecimal} end to end — the
 * source computed the same figure through {@code Math.pow} and {@code Math.sqrt} on doubles
 * (finding F3), which is a build failure here.
 */
class ConfidenceCalculatorTest {

    /** Dates with gaps of 78 and 82 days: inside no cadence's window, so regularity earns 0. */
    private static final List<LocalDate> IRREGULAR = List.of(
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 20), LocalDate.of(2026, 6, 10));

    /** Amounts whose spread is wide enough that the amount bonus is almost entirely lost. */
    private static final List<BigDecimal> WILD = List.of(
        new BigDecimal("1.00"), new BigDecimal("20.00"), new BigDecimal("60.00"));

    private final SubscriptionDetectionProperties properties =
        new SubscriptionDetectionProperties(null, null, null, null, null, null, null);
    private final ConfidenceCalculator calculator = new ConfidenceCalculator(properties);

    @Test
    @DisplayName("Three charges, a steady price and perfect spacing score a full 1.0")
    void theBestPossibleThreeChargeCandidateScoresOne() {
        BigDecimal score = calculator.calculate(monthlyDates(3), steady("9.99", 3),
            BillingCycle.MONTHLY);

        // 0.75 base + 0.30 amount + 0.20 regularity = 1.25, clamped to 1.
        assertThat(score).isEqualByComparingTo("1.0000");
        assertThat(score.scale()).as("reported at four decimal places").isEqualTo(4);
    }

    @Test
    @DisplayName("Four charges alone reach the ceiling: more data stops adding confidence")
    void fourChargesAloneScoreOne() {
        assertThat(calculator.calculate(monthlyDates(4), steady("9.99", 4), BillingCycle.MONTHLY))
            .isEqualByComparingTo("1.0000");
        assertThat(calculator.calculate(monthlyDates(9), steady("9.99", 9), BillingCycle.MONTHLY))
            .isEqualByComparingTo("1.0000");
    }

    @Test
    @DisplayName("The regularity bonus is worth exactly 0.20, and nothing else moves with it")
    void theRegularityBonusIsWorthExactlyTwoTenths() {
        // Same three charges, same three amounts. The ONLY difference is whether the gaps fall
        // inside MONTHLY's window, and the amounts are wild enough that neither score clamps.
        BigDecimal irregular = calculator.calculate(IRREGULAR, WILD, BillingCycle.MONTHLY);
        BigDecimal regular = calculator.calculate(monthlyDates(3), WILD, BillingCycle.MONTHLY);

        assertThat(regular.subtract(irregular))
            .as("regular %s vs irregular %s", regular, irregular)
            .isBetween(new BigDecimal("0.1999"), new BigDecimal("0.2001"));
        assertThat(irregular)
            .as("and the irregular one still clears the 0.75 base on its three charges alone")
            .isGreaterThanOrEqualTo(new BigDecimal("0.7500"));
    }

    @Test
    @DisplayName("A wandering price costs the amount bonus in proportion to how far it wanders")
    void aWanderingPriceCostsTheAmountBonus() {
        BigDecimal steadyScore = calculator.calculate(IRREGULAR, steady("20.00", 3),
            BillingCycle.MONTHLY);
        BigDecimal wanderingScore = calculator.calculate(IRREGULAR,
            List.of(new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00")),
            BillingCycle.MONTHLY);
        BigDecimal wildScore = calculator.calculate(IRREGULAR, WILD, BillingCycle.MONTHLY);

        assertThat(wanderingScore).isLessThan(steadyScore);
        assertThat(wildScore).isLessThan(wanderingScore);
        assertThat(wanderingScore)
            .as("stddev/mean of 10/20/30 is about 0.41, so about 0.59 of the 0.30 bonus survives")
            .isBetween(new BigDecimal("0.9200"), new BigDecimal("0.9400"));
    }

    @Test
    @DisplayName("Every score is inside [0, 1] and clears the threshold for a real cadence")
    void everyScoreIsInsideTheUnitIntervalAndClearsTheThreshold() {
        for (BillingCycle cycle : BillingCycle.values()) {
            BigDecimal score = calculator.calculate(datesAtCadence(cycle, 3),
                steady("12.34", 3), cycle);

            assertThat(score).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
            assertThat(score)
                .as("%s: a three-charge candidate at a steady price is what the threshold is "
                    + "calibrated to admit", cycle)
                .isGreaterThanOrEqualTo(properties.confidenceThreshold());
        }
    }

    @Test
    @DisplayName("A zero mean and an empty amount list are handled rather than dividing by zero")
    void degenerateAmountsDoNotThrow() {
        List<LocalDate> dates = monthlyDates(3);

        assertThat(calculator.calculate(dates, steady("0.00", 3), BillingCycle.MONTHLY))
            .as("a mean of zero has no coefficient of variation; the bonus is simply not earned")
            .isEqualByComparingTo("0.9500");
        assertThat(calculator.calculate(dates, List.of(), BillingCycle.MONTHLY))
            .isEqualByComparingTo("0.9500");
    }

    @Test
    @DisplayName("A single charge earns no regularity bonus, because it spans no interval")
    void oneChargeHasNoIntervals() {
        assertThat(calculator.calculate(List.of(LocalDate.of(2026, 1, 1)), steady("9.99", 1),
            BillingCycle.MONTHLY))
            .as("0.25 base + 0.30 amount + 0 regularity")
            .isEqualByComparingTo("0.5500");
    }

    private static List<LocalDate> monthlyDates(int count) {
        return datesAtCadence(BillingCycle.MONTHLY, count);
    }

    private static List<LocalDate> datesAtCadence(BillingCycle cycle, int count) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate at = LocalDate.of(2020, 1, 15);
        for (int i = 0; i < count; i++) {
            dates.add(at);
            at = at.plusDays(cycle.cycleDays());
        }
        return dates;
    }

    private static List<BigDecimal> steady(String amount, int count) {
        List<BigDecimal> amounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            amounts.add(new BigDecimal(amount));
        }
        return amounts;
    }
}
