package finance.trove.notifications.preference;

import finance.trove.budgets.envelope.HouseholdAlertThresholds;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Collapse step 11's half of the step-8 swap: the budget alert threshold now comes from each
 * member's own {@code notification_preference} row instead of from one configured constant.
 *
 * <p>Step 8 wrote {@code EnvelopeSpendService} against
 * {@code trove.budgets.alert-threshold:0.80} and said so in its own javadoc — "the plan names
 * {@code notification_preference.budget_alert_threshold} as the source of this number, but that
 * table is V33 and belongs to collapse step 11". V31 is that table, and this is the lookup it was
 * waiting for.
 *
 * <p><strong>Why the envelope gets a SET of thresholds rather than one number.</strong> A
 * household is more than one person and its members do not have to agree about when they want to
 * hear. The crossing is a property of the ledger — "the envelope has just gone past this ratio for
 * the first time this month" — and it is only ever detected once, by comparing the previous stored
 * spend with the new one. If the envelope watched a single ratio (the lowest, say), a member whose
 * threshold is 95 % would never be alerted at all: by the time spend reaches 95 % the crossing at
 * 70 % has already happened and been consumed. So the envelope publishes on the crossing of ANY
 * member's ratio, and {@code NotificationEventListener} then decides, per member, whether that
 * member's own ratio has been reached. V32's dedup index makes the second question idempotent —
 * one alert per envelope, per member, per month — so a member is never told twice even though two
 * crossings of the same envelope can now both publish.
 *
 * <p>The bean lives here, in the notifications package, and the interface lives in
 * {@code finance.trove.budgets.envelope}. That direction is deliberate: budgets depends on an
 * interface it owns, notifications depends on budgets, and there is no cycle between the two
 * packages.
 */
@Component
public class PreferenceAlertThresholds implements HouseholdAlertThresholds {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final NotificationPreferenceRepository preferences;

    public PreferenceAlertThresholds(NotificationPreferenceRepository preferences) {
        this.preferences = preferences;
    }

    /**
     * Every distinct ratio the household's active members want to be told at.
     *
     * <p>The shipped default is always included. A member who has never opened the settings screen
     * has no row yet, and their row would carry the default if they had one; leaving the default
     * out would mean a household of two, one of whom has saved a threshold, silently stopped
     * alerting the other.
     */
    @Override
    @Transactional(readOnly = true)
    public Set<BigDecimal> fractionsFor(UUID householdId) {
        List<Short> percents = preferences.findThresholdPercentsForHousehold(householdId);
        Set<BigDecimal> fractions = new LinkedHashSet<>();
        fractions.add(HouseholdAlertThresholds.DEFAULT_FRACTION);
        for (Short percent : percents) {
            if (percent != null) {
                fractions.add(BigDecimal.valueOf(percent)
                    .divide(ONE_HUNDRED, 2, RoundingMode.UNNECESSARY));
            }
        }
        return Set.copyOf(fractions);
    }
}
