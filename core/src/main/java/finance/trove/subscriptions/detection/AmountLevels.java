package finance.trove.subscriptions.detection;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A merchant's charge amounts, split into the distinct price LEVELS they were charged at
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.7; finding F3).
 *
 * <p><strong>Why this replaces the source's coefficient-of-variation gate.</strong> The
 * pre-collapse detector rejected a merchant whose amounts varied by more than 5% (CV ≤ 0.05) over
 * a 90-day window. This step reads THIRTEEN months (§6 D2), and over thirteen months a perfectly
 * ordinary price rise — 9.99 to 12.99 — produces a CV of about 13%. The old gate would therefore
 * throw the merchant away entirely, and the feature could never report the very price change it
 * exists to report. Widening the window without changing the gate would have turned a working
 * detector into one that silently stopped detecting anything that had ever changed price.
 *
 * <p><strong>The rule instead.</strong> Walk the amounts newest-first and group them into
 * contiguous runs, starting a new run whenever a charge differs from the run's first member by
 * more than {@link #DEFAULT_LEVEL_TOLERANCE_PERCENT}%. What comes out is the price history in
 * reverse:
 * <ul>
 *   <li>one level — a subscription at a constant price;</li>
 *   <li>two levels — a subscription whose price changed once inside the window;</li>
 *   <li>three or more — not a subscription. A merchant charged at three different amounts is
 *       variable spending, and {@link #isSubscriptionLike()} is false.</li>
 * </ul>
 *
 * <p>{@link #currentAmount()} is the MEDIAN of the newest level, not a mean over the whole
 * history: a mean reports a figure the member was never charged the moment the price moves, and
 * it is the figure {@code nextChargeDate} is supposed to predict. The median rather than the last
 * charge because a single unusual amount inside the tolerance should not move the answer.
 *
 * <p>Every comparison and every division here is {@link BigDecimal}. There is no {@code double}
 * anywhere in this class, which is the point: §9 risk 6 makes binary floating point in the
 * domain a build failure, and the source did all of this arithmetic in {@code double}.
 */
public final class AmountLevels {

    /**
     * How far two charges may differ and still be the same price, as a percentage.
     *
     * <p>Five percent, the same number the source used for its CV gate — a monthly bill that
     * moves by less than a twentieth is a tax or a rounding difference, not a new price.
     */
    public static final BigDecimal DEFAULT_LEVEL_TOLERANCE_PERCENT = new BigDecimal("5");

    /** More levels than this and the merchant is variable spending, not a subscription. */
    public static final int MAX_LEVELS = 2;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    private final List<List<BigDecimal>> levelsNewestFirst;
    private final int scale;

    private AmountLevels(List<List<BigDecimal>> levelsNewestFirst, int scale) {
        this.levelsNewestFirst = levelsNewestFirst;
        this.scale = scale;
    }

    /**
     * Splits amounts into price levels.
     *
     * @param chronological   the charge amounts, oldest first — the order the ledger returns them
     * @param tolerancePercent how far a charge may sit from its level's first member
     * @param scale           the currency's minor-unit scale, used when a median falls between
     *                        two charges
     * @return the levels, newest first; never {@code null}
     */
    public static AmountLevels of(List<BigDecimal> chronological, BigDecimal tolerancePercent,
                                  int scale) {
        List<List<BigDecimal>> levels = new ArrayList<>();
        List<BigDecimal> current = null;
        BigDecimal reference = null;
        for (int i = chronological.size() - 1; i >= 0; i--) {
            BigDecimal amount = chronological.get(i);
            if (current == null || !withinTolerance(amount, reference, tolerancePercent)) {
                current = new ArrayList<>();
                levels.add(current);
                reference = amount;
            }
            current.add(amount);
        }
        return new AmountLevels(levels, scale);
    }

    /**
     * Whether {@code amount} is within {@code tolerancePercent} of {@code reference}.
     *
     * <p>Cross-multiplied rather than divided — {@code |a-r| * 100 <= tolerance * |r|} — so there
     * is no division, no rounding decision and no behaviour at {@code reference == 0}: a zero
     * reference makes the right-hand side zero, and only an exactly equal amount passes.
     */
    private static boolean withinTolerance(BigDecimal amount, BigDecimal reference,
                                           BigDecimal tolerancePercent) {
        return amount.subtract(reference).abs().multiply(HUNDRED)
            .compareTo(tolerancePercent.multiply(reference.abs())) <= 0;
    }

    /** How many distinct price levels the window holds. Zero only for an empty history. */
    public int levelCount() {
        return levelsNewestFirst.size();
    }

    /** Whether these amounts look like one subscription rather than variable spending. */
    public boolean isSubscriptionLike() {
        return !levelsNewestFirst.isEmpty() && levelsNewestFirst.size() <= MAX_LEVELS;
    }

    /** The price the member is paying now: the median of the newest level. */
    public BigDecimal currentAmount() {
        return median(levelsNewestFirst.getFirst());
    }

    /**
     * The price before the most recent change, when the window contains one.
     *
     * <p>Descriptive only. The row's {@code previous_amount} comes from what was STORED at the
     * last scan, not from here — otherwise a scan that re-read the same thirteen months would
     * keep re-announcing a change the member was told about months ago.
     */
    public Optional<BigDecimal> priorAmount() {
        return levelsNewestFirst.size() < 2 ? Optional.empty()
            : Optional.of(median(levelsNewestFirst.get(1)));
    }

    /**
     * The middle amount of a level; the mean of the two middle amounts when the count is even.
     *
     * <p>Both middles are inside the level's tolerance of each other by construction, so their
     * mean is inside it too and cannot invent a price far from one actually charged.
     */
    private BigDecimal median(List<BigDecimal> level) {
        List<BigDecimal> sorted = new ArrayList<>(level);
        sorted.sort(BigDecimal::compareTo);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2).setScale(scale, RoundingMode.HALF_UP);
        }
        return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
            .divide(TWO, new MathContext(scale + 12, RoundingMode.HALF_UP))
            .setScale(scale, RoundingMode.HALF_UP);
    }
}
