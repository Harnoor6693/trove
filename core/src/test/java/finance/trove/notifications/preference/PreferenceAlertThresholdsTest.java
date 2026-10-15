package finance.trove.notifications.preference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import finance.trove.budgets.envelope.HouseholdAlertThresholds;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Collapse step 11's half of the step-8 swap. */
@ExtendWith(MockitoExtension.class)
class PreferenceAlertThresholdsTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();

    @Mock
    private NotificationPreferenceRepository preferences;

    @Test
    @DisplayName("every member's saved threshold is watched, as an exact fraction")
    void everySavedThresholdBecomesAFraction() {
        when(preferences.findThresholdPercentsForHousehold(HOUSEHOLD))
            .thenReturn(List.of((short) 70, (short) 95));

        assertThat(new PreferenceAlertThresholds(preferences).fractionsFor(HOUSEHOLD))
            .containsExactlyInAnyOrder(new BigDecimal("0.70"), new BigDecimal("0.95"),
                HouseholdAlertThresholds.DEFAULT_FRACTION);
    }

    @Test
    @DisplayName("the shipped default is always watched, for the member who has no row yet")
    void theDefaultIsAlwaysIncluded() {
        // A household of two where one has saved a threshold and the other has never opened the
        // settings screen: leaving the default out would silently stop alerting the second.
        // Watching one ratio too many costs an event that notifies nobody; watching one too few
        // costs a member an alert they asked for.
        when(preferences.findThresholdPercentsForHousehold(HOUSEHOLD))
            .thenReturn(List.of((short) 60));

        assertThat(new PreferenceAlertThresholds(preferences).fractionsFor(HOUSEHOLD))
            .containsExactlyInAnyOrder(new BigDecimal("0.60"),
                HouseholdAlertThresholds.DEFAULT_FRACTION);
    }

    @Test
    @DisplayName("a household with no preference rows at all still watches the default")
    void anEmptyHouseholdStillAlerts() {
        when(preferences.findThresholdPercentsForHousehold(HOUSEHOLD)).thenReturn(List.of());

        assertThat(new PreferenceAlertThresholds(preferences).fractionsFor(HOUSEHOLD))
            .containsExactly(HouseholdAlertThresholds.DEFAULT_FRACTION);
    }

    @Test
    @DisplayName("duplicate percentages collapse to one ratio")
    void duplicatesCollapse() {
        when(preferences.findThresholdPercentsForHousehold(HOUSEHOLD))
            .thenReturn(List.of((short) 90, (short) 90));

        assertThat(new PreferenceAlertThresholds(preferences).fractionsFor(HOUSEHOLD))
            .hasSize(1);
    }

    @Test
    @DisplayName("the default matches notification_preference's own column default")
    void theDefaultAgreesWithTheSchema() {
        // Two places state 90 %: V31's column default and this constant. If they drift, a
        // household where nobody has saved anything alerts at a different point from one where
        // everybody has saved the default.
        assertThat(HouseholdAlertThresholds.DEFAULT_FRACTION)
            .isEqualByComparingTo(BigDecimal.valueOf(
                    NotificationPreference.DEFAULT_BUDGET_ALERT_THRESHOLD_PERCENT)
                .divide(new BigDecimal("100"), 2, java.math.RoundingMode.UNNECESSARY));
    }
}
