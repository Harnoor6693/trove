package finance.trove.budgets.envelope;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Turns an envelope's amounts and a reader's "today" into {@link EnvelopeHealth}.
 *
 * <p>Stateless and free of Spring annotations, exactly as the pre-collapse calculator was. Two
 * things changed, and both were real defects:
 *
 * <ol>
 *   <li><strong>The month is a parameter.</strong> The source read {@code today.getDayOfMonth()}
 *       whatever month the period covered, so a September envelope opened on the 5th of October
 *       reported a 5 %-elapsed pace and claimed the household was comfortably under pace on a
 *       month that had been over for five days. The pace of a finished month is 100 %, and of one
 *       that has not started, 0 %.</li>
 *   <li><strong>"Today" arrives from {@code UserClock}</strong>, in the reader's own timezone
 *       (gap P2-15). The source's caller passed {@code LocalDate.now()} with no zone, against a
 *       container that is UTC by policy.</li>
 * </ol>
 *
 * <p>The amounts are re-rounded to the envelope's currency scale on the way out, so a derived
 * figure can never carry more precision than the currency it is quoted in (§9 risk 6).
 */
public final class EnvelopeHealthCalculator {

    /** Spending is "over pace" past 110 % of the linear expectation, as in the source. */
    private static final BigDecimal OVER_PACE_THRESHOLD = new BigDecimal("1.10");

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private EnvelopeHealthCalculator() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Computes the position of {@code envelope} as of {@code today} in the reader's zone.
     *
     * @param envelope the row; its currency fixes the scale of every derived amount
     * @param month    the month its period covers
     * @param today    the reader's own calendar date, from {@code UserClock.today(userId)}
     * @return the computed position; never {@code null} and never stored
     */
    public static EnvelopeHealth calculate(Envelope envelope, YearMonth month, LocalDate today) {
        CurrencyCode currency = envelope.getCurrency();
        int daysInMonth = month.lengthOfMonth();
        int daysElapsed = daysElapsed(month, today, daysInMonth);

        int paceMarkerPercentage = BigDecimal.valueOf(daysElapsed).multiply(HUNDRED)
            .divide(BigDecimal.valueOf(daysInMonth), 0, RoundingMode.HALF_UP)
            .intValue();

        BigDecimal allocated = envelope.getAllocated();
        BigDecimal spent = envelope.getSpent();
        BigDecimal remaining = currency.normalise(
            allocated.add(envelope.getRolloverAmount()).subtract(spent).max(BigDecimal.ZERO));

        if (allocated.signum() == 0) {
            // Nothing budgeted: there is no ratio to report and no pace to be over. Said once,
            // here, rather than letting a divide-by-zero decide it further down.
            return new EnvelopeHealth(0, paceMarkerPercentage, false, remaining,
                currency.normalise(BigDecimal.ZERO));
        }

        int healthPercentage = spent.multiply(HUNDRED)
            .divide(allocated, 0, RoundingMode.HALF_UP)
            .intValue();
        BigDecimal expectedSpend = currency.normalise(allocated
            .multiply(BigDecimal.valueOf(daysElapsed))
            .divide(BigDecimal.valueOf(daysInMonth), currency.minorUnits() + 2,
                RoundingMode.HALF_UP));
        boolean overPace = spent.compareTo(expectedSpend.multiply(OVER_PACE_THRESHOLD)) > 0;

        return new EnvelopeHealth(healthPercentage, paceMarkerPercentage, overPace, remaining,
            expectedSpend);
    }

    /**
     * How much of {@code month} has passed for a reader whose date is {@code today}.
     *
     * <p>Clamped at both ends: a month already over is fully elapsed however long ago it ended,
     * and one that has not begun is not elapsed at all. Only the current month reads the day.
     */
    private static int daysElapsed(YearMonth month, LocalDate today, int daysInMonth) {
        YearMonth readerMonth = YearMonth.from(today);
        if (readerMonth.isAfter(month)) {
            return daysInMonth;
        }
        if (readerMonth.isBefore(month)) {
            return 0;
        }
        return today.getDayOfMonth();
    }
}
