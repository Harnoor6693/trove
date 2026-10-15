package finance.trove.budgets.envelope;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/**
 * Which shares of an envelope's allocation a household wants to be told about
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5 → §2.8, §6 D1 → D3).
 *
 * <p>This interface is the seam collapse step 8 left for collapse step 11. Step 8 had to publish
 * {@code BudgetThresholdCrossedEvent} before {@code notification_preference} existed, so it
 * compared against one configured constant and said so; V31 is that table and
 * {@code finance.trove.notifications.preference.PreferenceAlertThresholds} is the implementation
 * that reads it. The interface lives HERE, in the package that consumes it, so that budgets
 * depends on nothing in notifications and the two packages do not form a cycle.
 *
 * <h2>Why a set, and not a number</h2>
 * A household is more than one person and its members need not agree about when they want to hear.
 * A crossing is detected exactly once — by comparing the envelope's previous stored spend with the
 * one just computed — so an envelope that watched a single ratio would silently never alert
 * anybody whose own ratio is higher than it: by the time spend reaches 95 %, the crossing at 70 %
 * has already happened and been consumed. Watching every ratio the household holds means every
 * member's crossing is published; deciding WHICH members to tell is then the notification
 * listener's job, and V32's per-(user, type, key) unique index makes that idempotent, so nobody is
 * told twice however many crossings one envelope produces in a month.
 *
 * <p>Watching one ratio too many costs an event that notifies nobody. Watching one too few costs a
 * member an alert they asked for. The implementation errs in the first direction on purpose.
 */
@FunctionalInterface
public interface HouseholdAlertThresholds {

    /**
     * The shipped default, as a fraction: 90 %, matching {@code notification_preference}'s own
     * column default in V31.
     *
     * <p>It is stated here rather than in the entity so that a household with no preference row at
     * all — a fresh install between the first-run bootstrap and anybody opening the settings
     * screen — still alerts at the same point a saved default would.
     */
    BigDecimal DEFAULT_FRACTION = new BigDecimal("0.90");

    /**
     * Every distinct fraction of an envelope's allocation at which some member of this household
     * wants to be alerted. Never empty: the default is always among them.
     *
     * @param householdId the ledger whose members are asked
     */
    Set<BigDecimal> fractionsFor(UUID householdId);
}
