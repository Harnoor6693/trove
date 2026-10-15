package finance.trove.subscriptions.detection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns the day gaps between a merchant's consecutive charges into a {@link BillingCycle}.
 *
 * <p>A cycle matches when at least {@code SubscriptionDetectionProperties.intervalMatchRatio()} of
 * the gaps fall inside its tolerance window. Cycles are tried in declaration order and the first match
 * wins, which is well-defined because the windows do not overlap ({@code SubscriptionDetectionProperties}
 * refuses a configured tolerance that would make them).
 *
 * <p>At least two intervals — three charges — are required. One interval is a coincidence: any
 * two purchases from the same shop produce exactly one gap, and that gap lands inside some
 * cycle's window more often than not.
 *
 * <p><strong>Why the ratio and not a strict all-must-match rule.</strong> A subscription that
 * misses a month, or whose charge posts late over a holiday, still is one. With four intervals,
 * 70% means three of the four must match: gaps of 30, 30, 60 and 30 days classify as
 * {@code MONTHLY} — the 60 is a missed month, not evidence of a quarterly cadence, and 60 is
 * outside {@code QUARTERLY}'s window anyway.
 *
 * <p>The arithmetic is integer and {@link BigDecimal} throughout. The ratio test is written as a
 * multiplication rather than a division so that it is exact: {@code matches >= ratio * total},
 * never {@code matches / total >= ratio}, which would have to choose a rounding mode.
 */
@Component
public class IntervalClassifier {

    /**
     * The fewest intervals a cadence can be read from — two, which is three charges.
     *
     * <p>Named rather than inlined because {@code SubscriptionDetectionService} states the same
     * rule as a number of CHARGES, and the two must not be able to drift apart.
     */
    public static final int MIN_INTERVALS = 2;

    private final SubscriptionDetectionProperties properties;

    public IntervalClassifier(SubscriptionDetectionProperties properties) {
        this.properties = properties;
    }

    /**
     * @param intervalDays the gaps, in days, between consecutive charges in date order
     * @return the matching cycle, or empty when fewer than two intervals were given or no cycle
     *         reached the match ratio
     */
    public Optional<BillingCycle> classify(List<Integer> intervalDays) {
        if (intervalDays == null || intervalDays.size() < MIN_INTERVALS) {
            return Optional.empty();
        }
        BigDecimal required =
            properties.intervalMatchRatio().multiply(BigDecimal.valueOf(intervalDays.size()));
        for (BillingCycle cycle : BillingCycle.values()) {
            int tolerance = properties.toleranceDays(cycle);
            long matches = intervalDays.stream()
                .filter(gap -> Math.abs(gap - cycle.cycleDays()) <= tolerance)
                .count();
            if (BigDecimal.valueOf(matches).compareTo(required) >= 0) {
                return Optional.of(cycle);
            }
        }
        return Optional.empty();
    }
}
