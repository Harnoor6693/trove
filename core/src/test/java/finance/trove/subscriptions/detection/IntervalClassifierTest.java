package finance.trove.subscriptions.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The cadence classifier, cadence by cadence and edge by edge.
 *
 * <p>These are behaviour tests, not status-code tests: each one names a gap pattern a real ledger
 * produces and the cadence a member would say it is.
 */
class IntervalClassifierTest {

    private final SubscriptionDetectionProperties properties =
        new SubscriptionDetectionProperties(null, null, null, null, null, null, null);
    private final IntervalClassifier classifier = new IntervalClassifier(properties);

    @ParameterizedTest(name = "gaps of {0} days classify as {1}")
    @CsvSource({
        "'7,7,7',       WEEKLY",
        "'4,7,10',      WEEKLY",
        "'14,14,14',    BIWEEKLY",
        "'11,14,17',    BIWEEKLY",
        "'30,30,30',    MONTHLY",
        "'28,30,31',    MONTHLY",
        "'27,33',       MONTHLY",
        "'31,28',       MONTHLY",
        "'90,90,90',    QUARTERLY",
        "'85,95,91',    QUARTERLY",
        "'365,365',     ANNUAL",
        "'355,375',     ANNUAL",
    })
    @DisplayName("Each cadence's tolerance window, at its centre and at both its edges")
    void everyCadenceClassifiesInsideItsTolerance(String gaps, BillingCycle expected) {
        assertThat(classifier.classify(intervals(gaps))).contains(expected);
    }

    @Test
    @DisplayName("28-, 30- and 31-day gaps are all MONTHLY, not a biweekly with a large error")
    void monthLengthVariationIsAbsorbedByTheTolerance() {
        // The three real month lengths a card sees in a row over February-April. The classifier
        // must not read them as anything but MONTHLY: with a per-cadence tolerance of three days
        // they all sit inside 30 +/- 3, and none of them is within seven of BIWEEKLY's fourteen.
        assertThat(classifier.classify(List.of(31, 28, 31, 30))).contains(BillingCycle.MONTHLY);
        assertThat(classifier.classify(List.of(28, 31))).contains(BillingCycle.MONTHLY);
    }

    @Test
    @DisplayName("A missed month is still MONTHLY, and is never read as QUARTERLY")
    void aMissedMonthDoesNotBecomeQuarterly() {
        // 30, 30, 60, 30: the 60 is one skipped charge. Three of the four gaps match MONTHLY,
        // which clears the 70% ratio, and 60 is nowhere near QUARTERLY's 90 +/- 5 window.
        assertThat(classifier.classify(List.of(30, 30, 60, 30)))
            .as("a subscription that misses a month is still a monthly subscription")
            .contains(BillingCycle.MONTHLY);

        assertThat(classifier.classify(List.of(30, 60, 30)))
            .as("with only three gaps the ratio is not met, but the answer must still never be "
                + "QUARTERLY — inventing a cadence from a gap nobody was charged at is worse "
                + "than admitting there is no pattern")
            .isNotEqualTo(java.util.Optional.of(BillingCycle.QUARTERLY));
    }

    @Test
    @DisplayName("Fourteen days is BIWEEKLY, never WEEKLY, even though WEEKLY is tried first")
    void fourteenDaysIsNotTwoWeeklyCharges() {
        assertThat(classifier.classify(List.of(14, 14, 14))).contains(BillingCycle.BIWEEKLY);
    }

    @ParameterizedTest
    @ValueSource(ints = {26, 34, 20, 45, 120, 200})
    @DisplayName("A gap in nobody's tolerance window classifies as nothing at all")
    void aGapOutsideEveryWindowIsNotACadence(int gap) {
        assertThat(classifier.classify(List.of(gap, gap, gap)))
            .as("%d days is between two cadences; guessing one would put a subscription on a "
                + "member's screen that their ledger does not support", gap)
            .isEmpty();
    }

    @Test
    @DisplayName("Two charges give one interval, and one interval is not a cadence")
    void oneIntervalIsACoincidence() {
        assertThat(classifier.classify(List.of(30))).isEmpty();
        assertThat(classifier.classify(List.of())).isEmpty();
        assertThat(classifier.classify(null)).isEmpty();
        assertThat(IntervalClassifier.MIN_INTERVALS).isEqualTo(2);
        assertThat(SubscriptionDetectionProperties.DEFAULT_MINIMUM_CHARGES)
            .as("the charge minimum and the interval minimum must not drift apart")
            .isEqualTo(IntervalClassifier.MIN_INTERVALS + 1);
    }

    @Test
    @DisplayName("Ragged spacing that never reaches 70% classifies as nothing")
    void theRatioIsAFloorAndNotASuggestion() {
        // Two of four gaps are monthly: 50%, under the 0.70 the properties record carries.
        assertThat(classifier.classify(List.of(30, 30, 200, 210))).isEmpty();
    }

    @Test
    @DisplayName("A configured tolerance widens the window it names and no other")
    void theToleranceIsConfigurable() {
        IntervalClassifier lenient = new IntervalClassifier(new SubscriptionDetectionProperties(
            null, null, null, null, null, null, Map.of(BillingCycle.MONTHLY, 6)));

        assertThat(lenient.classify(List.of(36, 36, 36)))
            .as("36 days is outside the default 30 +/- 3 and inside a configured 30 +/- 6")
            .contains(BillingCycle.MONTHLY);
        assertThat(classifier.classify(List.of(36, 36, 36)))
            .as("and the default classifier is unaffected by another instance's configuration")
            .isEmpty();
    }

    private static List<Integer> intervals(String csv) {
        return java.util.Arrays.stream(csv.split(",")).map(String::trim)
            .map(Integer::valueOf).toList();
    }
}
