package finance.trove.subscriptions.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The calendar arithmetic behind {@code next_charge_date}, and the month-length edges that are the
 * whole reason it is calendar arithmetic rather than day counting.
 *
 * <p>The pre-collapse detector projected {@code lastSeen.plusDays(cycleDays)}, which drifts: a
 * monthly charge on the 31st lands on the 2nd of the month after next, and an annual one slips a
 * day every leap year. Each case below is one of those drifts, written as the date a card is
 * actually charged on.
 */
class BillingCycleTest {

    @ParameterizedTest(name = "{0} last seen {1} is next expected {2}")
    @CsvSource({
        // The clamp is the point: LocalDate.plusMonths never invents 31 February, it gives the
        // month's last day, which is the day the card is charged.
        "MONTHLY,   2026-01-31, 2026-02-28",
        "MONTHLY,   2028-01-31, 2028-02-29",
        "MONTHLY,   2026-01-30, 2026-02-28",
        "MONTHLY,   2026-03-31, 2026-04-30",
        "MONTHLY,   2026-02-28, 2026-03-28",
        // A leap day advanced by a year is 28 February, not 1 March.
        "ANNUAL,    2028-02-29, 2029-02-28",
        "ANNUAL,    2026-03-04, 2027-03-04",
        // BIWEEKLY is exactly fourteen days and therefore drifts across month ends by
        // construction, which is correct: a fortnightly charge is not a monthly one.
        "BIWEEKLY,  2026-01-25, 2026-02-08",
        "BIWEEKLY,  2026-02-22, 2026-03-08",
        "BIWEEKLY,  2028-02-20, 2028-03-05",
        "WEEKLY,    2026-12-29, 2027-01-05",
        "QUARTERLY, 2026-11-30, 2027-02-28",
        "QUARTERLY, 2027-11-30, 2028-02-29",
    })
    @DisplayName("A cycle advances by the calendar, clamping to the month's last day")
    void advanceClampsToTheMonthsLastDay(BillingCycle cycle, LocalDate lastSeen,
                                         LocalDate expected) {
        assertThat(cycle.advance(lastSeen)).isEqualTo(expected);
    }

    @Test
    @DisplayName("A daylight-saving transition cannot move a date-only projection")
    void aDaylightSavingTransitionLeavesTheDateAlone() {
        // 8 March 2026 is the spring-forward Sunday in America/Toronto: 02:00 does not exist that
        // day. next_charge_date is a DATE, so nothing here has an hour for the transition to take
        // away — but a future refactor that routed this through an Instant would silently move
        // the projection by a day for anyone charged near midnight, and this is the assertion
        // that would catch it.
        ZoneId toronto = ZoneId.of("America/Toronto");
        LocalDate beforeSpringForward = LocalDate.of(2026, 2, 8);
        LocalDate projected = BillingCycle.MONTHLY.advance(beforeSpringForward);

        assertThat(projected).isEqualTo(LocalDate.of(2026, 3, 8));
        assertThat(ZonedDateTime.of(projected.atStartOfDay(), toronto).toLocalDate())
            .as("the spring-forward day starts at 00:00 like any other; only 02:00 is missing")
            .isEqualTo(projected);
        assertThat(BillingCycle.MONTHLY.advance(LocalDate.of(2026, 10, 1)))
            .as("and the autumn fall-back day is equally uneventful for a date")
            .isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @ParameterizedTest
    @EnumSource(BillingCycle.class)
    @DisplayName("Advancing always moves forward, and never by less than the cycle allows")
    void advanceAlwaysMovesForward(BillingCycle cycle) {
        LocalDate from = LocalDate.of(2026, 1, 31);
        LocalDate to = cycle.advance(from);

        assertThat(to).isAfter(from);
        assertThat(to.toEpochDay() - from.toEpochDay())
            .as("%s should land within its own tolerance of %d days", cycle, cycle.cycleDays())
            .isBetween((long) cycle.cycleDays() - cycle.defaultToleranceDays(),
                (long) cycle.cycleDays() + cycle.defaultToleranceDays());
    }

    @ParameterizedTest(name = "{0} is {1}/{2} of a month")
    @CsvSource({
        "WEEKLY,    52, 12",
        "BIWEEKLY,  26, 12",
        "MONTHLY,    1,  1",
        "QUARTERLY,  1,  3",
        "ANNUAL,     1, 12",
    })
    @DisplayName("The monthly fraction is exact, not the source's rounded 4.33 and 2.17")
    void theMonthlyFractionIsExact(BillingCycle cycle, int numerator, int denominator) {
        assertThat(cycle.monthlyNumerator()).isEqualByComparingTo(BigDecimal.valueOf(numerator));
        assertThat(cycle.monthlyDenominator())
            .isEqualByComparingTo(BigDecimal.valueOf(denominator));
    }

    @Test
    @DisplayName("A weekly bill is charged 52 times a year, so 4.33 was never the right factor")
    void theWeeklyFactorIsFiftyTwoOverTwelve() {
        BigDecimal factor = BillingCycle.WEEKLY.monthlyNumerator()
            .divide(BillingCycle.WEEKLY.monthlyDenominator(), java.math.MathContext.DECIMAL64);

        assertThat(factor)
            .as("52/12 is 4.333..., and the source's literal 4.33 is short by 0.0033 on every "
                + "weekly subscription in the run-rate")
            .isGreaterThan(new BigDecimal("4.33"))
            .isLessThan(new BigDecimal("4.34"));
    }

    @ParameterizedTest
    @EnumSource(BillingCycle.class)
    @DisplayName("Every cadence's tolerance is smaller than the cadence itself")
    void everyToleranceIsNarrowerThanItsCycle(BillingCycle cycle) {
        assertThat(cycle.defaultToleranceDays())
            .as("a tolerance as wide as the cycle would overlap the neighbouring cadence's "
                + "window and make the classifier's first-match-wins arbitrary")
            .isGreaterThanOrEqualTo(0)
            .isLessThan(cycle.cycleDays());
    }
}
