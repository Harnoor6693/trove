package finance.trove.budgets.envelope;

import java.math.BigDecimal;

/**
 * An envelope's spending position, computed on every read and never stored.
 *
 * <p>It is not persisted because every one of these five numbers depends on where "now" falls
 * inside the period's month, which is a fact about the clock — and, since collapse step 3, about
 * the reader's own timezone — rather than about the row.
 *
 * @param healthPercentage     {@code spent / (allocated + rolloverAmount)} as a percentage, rounded;
 *                             above 100 means over budget, and zero when that sum is zero
 * @param paceMarkerPercentage how far through the period's month the reader currently is, 0–100
 * @param overPace             {@code spent} exceeds 110 % of the linear-pace expectation; the
 *                             10 % buffer absorbs ordinary day-to-day variance
 * @param remainingAmount      {@code max(0, allocated + rolloverAmount - spent)}
 * @param expectedSpend        what would have been spent under perfectly linear spending so far
 */
public record EnvelopeHealth(int healthPercentage, int paceMarkerPercentage, boolean overPace,
                             BigDecimal remainingAmount, BigDecimal expectedSpend) {
}
