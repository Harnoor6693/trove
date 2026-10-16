package finance.trove.subscriptions.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Period;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The one configuration holder for subscription detection: its defaults, and the values it
 * refuses at context refresh rather than at 02:00 on a scan nobody is watching.
 */
class SubscriptionDetectionPropertiesTest {

    private static final SubscriptionDetectionProperties DEFAULTS =
        new SubscriptionDetectionProperties(null, null, null, null, null, null, null);

    @Test
    @DisplayName("Every value has a default, so no application.yml entry is needed to work")
    void everyValueHasADefault() {
        assertThat(DEFAULTS.detectionWindow())
            .as("25 months: the classifier needs three charges, which for an ANNUAL one span two "
                + "years; the source read ninety days and could never detect an annual "
                + "subscription, and a 13-month window still could not (CI-45)")
            .isEqualTo(Period.ofMonths(25));
        assertThat(DEFAULTS.minimumCharges()).isEqualTo(3);
        assertThat(DEFAULTS.intervalMatchRatio()).isEqualByComparingTo("0.70");
        assertThat(DEFAULTS.levelTolerancePercent()).isEqualByComparingTo("5");
        assertThat(DEFAULTS.confidenceThreshold()).isEqualByComparingTo("0.75");
        assertThat(DEFAULTS.upcomingDays()).isEqualTo(14);
    }

    @ParameterizedTest
    @EnumSource(BillingCycle.class)
    @DisplayName("An unnamed cadence falls back to its own default tolerance")
    void anUnnamedCadenceUsesItsOwnDefault(BillingCycle cycle) {
        assertThat(DEFAULTS.toleranceDays(cycle)).isEqualTo(cycle.defaultToleranceDays());
    }

    @Test
    @DisplayName("A named cadence is overridden, and the others are left alone")
    void oneOverrideDoesNotDisturbTheRest() {
        SubscriptionDetectionProperties tuned = new SubscriptionDetectionProperties(
            null, null, null, null, null, null, Map.of(BillingCycle.QUARTERLY, 9));

        assertThat(tuned.toleranceDays(BillingCycle.QUARTERLY)).isEqualTo(9);
        assertThat(tuned.toleranceDays(BillingCycle.MONTHLY))
            .isEqualTo(BillingCycle.MONTHLY.defaultToleranceDays());
    }

    @Test
    @DisplayName("A tolerance as wide as its own cadence is refused, not silently accepted")
    void anOverlappingToleranceIsRefused() {
        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            null, null, null, null, null, null, Map.of(BillingCycle.MONTHLY, 30)))
            .as("30 +/- 30 swallows BIWEEKLY's whole window and makes first-match-wins arbitrary")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cadence-tolerance-days");

        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            null, null, null, null, null, null, Map.of(BillingCycle.WEEKLY, -1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("A window that is zero, negative or missing charges is refused")
    void anImpossibleWindowIsRefused() {
        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            Period.ZERO, null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("detection-window");

        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            Period.ofMonths(-1), null, null, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            null, 2, null, null, null, null, null))
            .as("two charges give one interval, and one interval is a coincidence")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minimum-charges");
    }

    @Test
    @DisplayName("A ratio or threshold outside its range is refused")
    void anImpossibleFractionIsRefused() {
        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            null, null, new BigDecimal("1.5"), null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("interval-match-ratio");

        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            null, null, BigDecimal.ZERO, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            null, null, null, new BigDecimal("-1"), null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("level-tolerance-percent");

        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            null, null, null, null, new BigDecimal("1.01"), null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("confidence-threshold");

        assertThatThrownBy(() -> new SubscriptionDetectionProperties(
            null, null, null, null, null, 0, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("upcoming-days");
    }

    @Test
    @DisplayName("The tolerance map it holds is its own, not the caller's")
    void theToleranceMapIsCopied() {
        java.util.Map<BillingCycle, Integer> caller = new java.util.EnumMap<>(BillingCycle.class);
        caller.put(BillingCycle.MONTHLY, 5);
        SubscriptionDetectionProperties properties = new SubscriptionDetectionProperties(
            null, null, null, null, null, null, caller);

        caller.put(BillingCycle.MONTHLY, 29);

        assertThat(properties.toleranceDays(BillingCycle.MONTHLY))
            .as("a configuration record a caller can still mutate is not a configuration record")
            .isEqualTo(5);
    }
}
