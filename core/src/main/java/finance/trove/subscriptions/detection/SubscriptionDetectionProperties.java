package finance.trove.subscriptions.detection;

import java.math.BigDecimal;
import java.time.Period;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every number subscription detection can be tuned by, in one place
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 "D2 — subscription scan").
 *
 * <p><strong>One holder, deliberately.</strong> An earlier draft of this step carried two
 * overlapping configuration classes — this record and a {@code SubscriptionTuning} component —
 * both binding under {@code trove.subscriptions.*} with their own defaults for the same keys and
 * two disjoint sets of consumers. Two holders of one prefix means an operator who sets
 * {@code confidence-threshold} changes the behaviour of half the detector, which is worse than no
 * configurability at all. {@code SubscriptionTuning} is deleted; this record is the only one.
 *
 * <p><strong>Every value carries its own default here, and nothing is added to
 * {@code application.yml}.</strong> Collapse steps 8, 9 and 10 run in parallel worktrees and no
 * step of this wave owns that file, so a property added to it is a merge conflict at best and a
 * silently lost edit at worst. A record bound by {@code @ConfigurationPropertiesScan} with
 * defaults in its canonical constructor needs no yml entry to work and picks one up the moment an
 * operator adds it — the same shape {@code PurgeJobs.reloadRateLimits} uses for
 * {@code trove.rate-limit.reload-interval}. The keys are named in the step's merge notes so they
 * can be promoted into the shipped configuration later.
 *
 * <p>Defaults, and why each is what it is:
 * <ul>
 *   <li>{@code trove.subscriptions.detection-window} — {@code P13M}, thirteen months (§6 D2). The
 *       thirteenth month is the point: twelve would leave an ANNUAL charge with exactly one
 *       observation in the window on the day before its anniversary, and one charge is no cadence
 *       at all. The source read ninety days and could therefore never detect an annual
 *       subscription.</li>
 *   <li>{@code trove.subscriptions.minimum-charges} — {@code 3}. Two charges give one interval,
 *       and one interval is a coincidence: any two purchases from the same shop are some number
 *       of days apart, and that number lands inside some cadence's window more often than
 *       not.</li>
 *   <li>{@code trove.subscriptions.interval-match-ratio} — {@code 0.70}. The fraction of gaps
 *       that must fall inside a cadence's tolerance window. Not "all of them": a subscription
 *       that misses a month, or whose charge posts late over a holiday, still is one.</li>
 *   <li>{@code trove.subscriptions.level-tolerance-percent} — {@code 5}. How far two charges may
 *       differ and still be the same price; see {@link AmountLevels}.</li>
 *   <li>{@code trove.subscriptions.confidence-threshold} — {@code 0.75}, the source's number.
 *       With the three-charge minimum above, the score is in practice always at or above this; it
 *       is a floor that states intent, not a gate that does most of the work.</li>
 *   <li>{@code trove.subscriptions.upcoming-days} — {@code 14}, the window
 *       {@code GET /v1/subscriptions/summary} reports upcoming charges over.</li>
 *   <li>{@code trove.subscriptions.cadence-tolerance-days.<cycle>} — each cadence's own
 *       {@link BillingCycle#defaultToleranceDays()}. An override is validated against that
 *       cadence's length, because a tolerance as wide as the cycle would make two cadences'
 *       windows overlap and the classifier's "first match wins" arbitrary.</li>
 * </ul>
 *
 * <p>A record rather than a class because {@code ArchitectureTest} requires it: the canonical
 * constructor is where a bad value fails the context refresh instead of surfacing at 02:00 on a
 * scan nobody is watching.
 *
 * @param detectionWindow       how far back a scan reads
 * @param minimumCharges        how many charges a merchant needs before a cadence is attempted
 * @param intervalMatchRatio    the fraction of gaps that must fall inside a cadence's window
 * @param levelTolerancePercent how far two charges may differ and still be one price
 * @param confidenceThreshold   the minimum score a candidate must reach
 * @param upcomingDays          the summary's upcoming-charge window, in days
 * @param cadenceToleranceDays  per-cadence tolerance overrides; absent entries use the cadence's
 *                              own default
 */
@ConfigurationProperties("trove.subscriptions")
public record SubscriptionDetectionProperties(Period detectionWindow,
                                              Integer minimumCharges,
                                              BigDecimal intervalMatchRatio,
                                              BigDecimal levelTolerancePercent,
                                              BigDecimal confidenceThreshold,
                                              Integer upcomingDays,
                                              Map<BillingCycle, Integer> cadenceToleranceDays) {

    /** Thirteen months — long enough to see an ANNUAL charge twice. */
    public static final Period DEFAULT_WINDOW = Period.ofMonths(13);

    /**
     * Three charges, so the classifier has its {@link IntervalClassifier#MIN_INTERVALS} to work
     * from. Derived rather than written as {@code 3}, so the two rules cannot drift apart.
     */
    public static final int DEFAULT_MINIMUM_CHARGES = IntervalClassifier.MIN_INTERVALS + 1;

    /** Seven gaps in ten must match the cadence. */
    public static final BigDecimal DEFAULT_INTERVAL_MATCH_RATIO = new BigDecimal("0.70");

    /** The source's confidence floor, kept. */
    public static final BigDecimal DEFAULT_CONFIDENCE_THRESHOLD = new BigDecimal("0.75");

    /** Fourteen days, the summary's upcoming window. */
    public static final int DEFAULT_UPCOMING_DAYS = 14;

    public SubscriptionDetectionProperties {
        detectionWindow = detectionWindow == null ? DEFAULT_WINDOW : detectionWindow;
        minimumCharges = minimumCharges == null ? DEFAULT_MINIMUM_CHARGES : minimumCharges;
        intervalMatchRatio = intervalMatchRatio == null
            ? DEFAULT_INTERVAL_MATCH_RATIO : intervalMatchRatio;
        levelTolerancePercent = levelTolerancePercent == null
            ? AmountLevels.DEFAULT_LEVEL_TOLERANCE_PERCENT : levelTolerancePercent;
        confidenceThreshold = confidenceThreshold == null
            ? DEFAULT_CONFIDENCE_THRESHOLD : confidenceThreshold;
        upcomingDays = upcomingDays == null ? DEFAULT_UPCOMING_DAYS : upcomingDays;
        cadenceToleranceDays = withCadenceDefaults(cadenceToleranceDays);

        if (detectionWindow.isZero() || detectionWindow.isNegative()) {
            throw new IllegalArgumentException(
                "trove.subscriptions.detection-window must be positive, got " + detectionWindow);
        }
        if (minimumCharges < DEFAULT_MINIMUM_CHARGES) {
            throw new IllegalArgumentException(
                "trove.subscriptions.minimum-charges must be at least " + DEFAULT_MINIMUM_CHARGES
                    + "; two charges give one interval, and one interval is a coincidence. Got "
                    + minimumCharges);
        }
        requireFraction(intervalMatchRatio, "interval-match-ratio");
        if (levelTolerancePercent.signum() < 0) {
            throw new IllegalArgumentException(
                "trove.subscriptions.level-tolerance-percent cannot be negative, got "
                    + levelTolerancePercent);
        }
        if (confidenceThreshold.signum() < 0 || confidenceThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                "trove.subscriptions.confidence-threshold must be in [0, 1], got "
                    + confidenceThreshold);
        }
        if (upcomingDays < 1) {
            throw new IllegalArgumentException(
                "trove.subscriptions.upcoming-days must be at least 1, got " + upcomingDays);
        }
    }

    /** The tolerance around this cadence's nominal length, in days. */
    public int toleranceDays(BillingCycle cycle) {
        return cadenceToleranceDays.getOrDefault(cycle, cycle.defaultToleranceDays());
    }

    /**
     * Fills in every cadence the operator did not name, and refuses a value that would make two
     * cadences' tolerance windows overlap.
     *
     * <p>The classifier tries cadences in declaration order and takes the first match, which is
     * only well defined while the windows are disjoint. A MONTHLY tolerance of 30 days would put
     * a 14-day gap inside MONTHLY's window as well as BIWEEKLY's, and which one a merchant got
     * would depend on an enum's declaration order rather than on its charges.
     */
    private static Map<BillingCycle, Integer> withCadenceDefaults(
        Map<BillingCycle, Integer> configured) {

        Map<BillingCycle, Integer> merged = new EnumMap<>(BillingCycle.class);
        for (BillingCycle cycle : BillingCycle.values()) {
            Integer override = configured == null ? null : configured.get(cycle);
            int tolerance = override == null ? cycle.defaultToleranceDays() : override;
            if (tolerance < 0 || tolerance >= cycle.cycleDays()) {
                throw new IllegalArgumentException(
                    "trove.subscriptions.cadence-tolerance-days." + cycle
                        + " must be at least 0 and less than the cadence's own "
                        + cycle.cycleDays() + " days, or two cadences' windows would overlap; got "
                        + tolerance);
            }
            merged.put(cycle, tolerance);
        }
        return Map.copyOf(merged);
    }

    private static void requireFraction(BigDecimal value, String key) {
        if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                "trove.subscriptions." + key + " must be a fraction in (0, 1]; got " + value);
        }
    }
}
