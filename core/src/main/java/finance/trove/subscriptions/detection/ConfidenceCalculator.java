package finance.trove.subscriptions.detection;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Scores a candidate subscription in {@code [0, 1]} from three things: how many charges were
 * observed, how alike their amounts are, and how regular their spacing is.
 *
 * <ul>
 *   <li><strong>Base</strong>, 0 to 1 — {@code min(charges / 4, 1)}. Four observations is where
 *       more data stops adding confidence.</li>
 *   <li><strong>Amount bonus</strong>, 0 to 0.30 — {@code (1 - stddev/mean) * 0.30}, clamped.
 *       A perfectly steady price earns the full bonus.</li>
 *   <li><strong>Regularity bonus</strong>, 0 to 0.20 — the fraction of gaps inside the cycle's
 *       tolerance window, times 0.20.</li>
 * </ul>
 *
 * <p>The three sum to at most 1.5 and the result is clamped to 1, which is deliberate: a
 * subscription with four charges alone already scores 1.0, so the two bonuses are what lift a
 * three-charge candidate over the 0.75 threshold. A three-charge candidate with a steady price and
 * perfect spacing scores {@code 0.75 + 0.30 + 0.20}, clamped to 1.0; one with a wandering price
 * and ragged spacing scores 0.75 and only just survives.
 *
 * <p><strong>Every step is {@link BigDecimal}.</strong> The source computed this in {@code double}
 * (finding F3) — including {@code Math.pow} and {@code Math.sqrt} over amounts converted with
 * {@code BigDecimal::doubleValue}. {@link BigDecimal#sqrt(MathContext)} does the same job without
 * ever leaving decimal arithmetic, so a ledger figure never passes through IEEE-754 (§9 risk 6).
 */
@Component
public class ConfidenceCalculator {

    /** Enough working digits that the square root is exact to far beyond the four we keep. */
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private static final BigDecimal FULL_CONFIDENCE_CHARGES = BigDecimal.valueOf(4);
    private static final BigDecimal AMOUNT_BONUS_WEIGHT = new BigDecimal("0.30");
    private static final BigDecimal REGULARITY_BONUS_WEIGHT = new BigDecimal("0.20");

    /** The scale the score is reported at. Four digits is well past anything that changes a gate. */
    private static final int SCORE_SCALE = 4;

    private final SubscriptionDetectionProperties properties;

    public ConfidenceCalculator(SubscriptionDetectionProperties properties) {
        this.properties = properties;
    }

    /**
     * @param datesOldestFirst the charge dates in order; at least one
     * @param amounts          the charge amounts, same size as {@code datesOldestFirst}
     * @param cycle            the cadence the classifier settled on
     * @return a score in {@code [0, 1]} at four decimal places
     */
    public BigDecimal calculate(List<LocalDate> datesOldestFirst, List<BigDecimal> amounts,
                                BillingCycle cycle) {
        BigDecimal base = BigDecimal.valueOf(datesOldestFirst.size())
            .divide(FULL_CONFIDENCE_CHARGES, MC)
            .min(BigDecimal.ONE);
        BigDecimal total = base.add(amountBonus(amounts))
            .add(regularityBonus(datesOldestFirst, cycle));
        return total.min(BigDecimal.ONE).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    /** {@code (1 - stddev/mean) * 0.30}, clamped to {@code [0, 0.30]}. */
    private BigDecimal amountBonus(List<BigDecimal> amounts) {
        if (amounts.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal count = BigDecimal.valueOf(amounts.size());
        BigDecimal mean = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(count, MC);
        if (mean.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal variance = amounts.stream()
            .map(amount -> amount.subtract(mean))
            .map(delta -> delta.multiply(delta))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .divide(count, MC);
        BigDecimal consistency = BigDecimal.ONE.subtract(variance.sqrt(MC).divide(mean, MC));
        return clampToUnit(consistency).multiply(AMOUNT_BONUS_WEIGHT);
    }

    /** The fraction of gaps inside the cycle's tolerance window, times 0.20. */
    private BigDecimal regularityBonus(List<LocalDate> datesOldestFirst, BillingCycle cycle) {
        int intervals = datesOldestFirst.size() - 1;
        if (intervals < 1) {
            return BigDecimal.ZERO;
        }
        int tolerance = properties.toleranceDays(cycle);
        long matching = 0;
        for (int i = 1; i < datesOldestFirst.size(); i++) {
            long gap = datesOldestFirst.get(i).toEpochDay()
                - datesOldestFirst.get(i - 1).toEpochDay();
            if (Math.abs(gap - cycle.cycleDays()) <= tolerance) {
                matching++;
            }
        }
        return BigDecimal.valueOf(matching).divide(BigDecimal.valueOf(intervals), MC)
            .multiply(REGULARITY_BONUS_WEIGHT);
    }

    private static BigDecimal clampToUnit(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }
}
