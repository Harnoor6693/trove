package finance.trove.subscriptions.detection;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The recurring cadences detection recognises, each with a canonical length in days and the
 * default tolerance the classifier allows around it.
 *
 * <p>The tolerance absorbs weekends, bank posting delays and month-length variation. It is what
 * makes 28-, 30- and 31-day gaps all read as {@link #MONTHLY} rather than as a biweekly charge
 * with a large error, and it is also what decides when a charge is genuinely late rather than
 * merely delayed — see {@code SubscriptionDetectionService}'s RED signal.
 *
 * <p>The tolerance values here are the DEFAULTS. {@code SubscriptionDetectionProperties} may
 * override them from configuration without any change to this file or to
 * {@code application.yml}.
 *
 * <h2>{@link #advance(LocalDate)} is calendar arithmetic, not day counting</h2>
 * <p>The pre-collapse detector projected the next charge as {@code lastSeen.plusDays(cycleDays)},
 * which drifts: a monthly subscription charged on the 31st would be projected onto the 2nd of the
 * month after next, and an annual one would slip a day every leap year. This method uses
 * {@code plusWeeks}/{@code plusMonths}/{@code plusYears}, whose end-of-month clamping is exactly
 * the behaviour wanted — 31 January plus one month is 28 February, or 29 in a leap year, which is
 * when the card is actually charged.
 */
public enum BillingCycle {

    /** Every seven days. A month is 52/12 weeks, not 4. */
    WEEKLY(7, 3, 52, 12),

    /** Every fourteen days. Drifts across month ends by construction, which is correct. */
    BIWEEKLY(14, 3, 26, 12),

    /** Once a calendar month, clamped to the month's last day. */
    MONTHLY(30, 3, 1, 1),

    /** Once every three calendar months. */
    QUARTERLY(90, 5, 1, 3),

    /** Once a calendar year. 29 February advances to 28 February. */
    ANNUAL(365, 10, 1, 12);

    private final int cycleDays;
    private final int defaultToleranceDays;
    private final BigDecimal monthlyNumerator;
    private final BigDecimal monthlyDenominator;

    BillingCycle(int cycleDays, int defaultToleranceDays, int monthlyNumerator,
                 int monthlyDenominator) {
        this.cycleDays = cycleDays;
        this.defaultToleranceDays = defaultToleranceDays;
        this.monthlyNumerator = BigDecimal.valueOf(monthlyNumerator);
        this.monthlyDenominator = BigDecimal.valueOf(monthlyDenominator);
    }

    /** The nominal gap between two charges, in days — what the classifier compares against. */
    public int cycleDays() {
        return cycleDays;
    }

    /**
     * The default tolerance around {@link #cycleDays()};
     * {@code SubscriptionDetectionProperties} may override it.
     */
    public int defaultToleranceDays() {
        return defaultToleranceDays;
    }

    /**
     * The numerator of the EXACT fraction that converts one charge at this cadence into a monthly
     * figure — {@code WEEKLY} 52/12, {@code BIWEEKLY} 26/12, {@code MONTHLY} 1/1,
     * {@code QUARTERLY} 1/3, {@code ANNUAL} 1/12.
     *
     * <p>A fraction rather than a decimal multiplier because the pre-collapse service used the
     * rounded constants {@code 4.33} and {@code 2.17}, each of which is wrong in the third
     * decimal place: a weekly bill is charged 52 times a year, not 51.96. Keeping numerator and
     * denominator apart lets {@code Money} do one multiplication and one division and round once,
     * at the currency's own scale, instead of inheriting a literal's error.
     */
    public BigDecimal monthlyNumerator() {
        return monthlyNumerator;
    }

    /** The denominator of {@link #monthlyNumerator()}'s fraction; never zero. */
    public BigDecimal monthlyDenominator() {
        return monthlyDenominator;
    }

    /**
     * {@code from} advanced by exactly one cycle, as a calendar date.
     *
     * <p>Date-only by construction, so a daylight-saving transition cannot move the result: there
     * is no time of day here for an hour to be added to or taken from.
     *
     * @param from the last charge date; never {@code null}
     * @return the next expected charge date
     */
    public LocalDate advance(LocalDate from) {
        return switch (this) {
            case WEEKLY -> from.plusWeeks(1);
            case BIWEEKLY -> from.plusWeeks(2);
            case MONTHLY -> from.plusMonths(1);
            case QUARTERLY -> from.plusMonths(3);
            case ANNUAL -> from.plusYears(1);
        };
    }
}
