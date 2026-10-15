package finance.trove.subscriptions.subscription;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.time.Instants;
import finance.trove.common.time.UserClock;
import finance.trove.subscriptions.detection.SubscriptionDetectionProperties;
import finance.trove.subscriptions.dto.SubscriptionCurrencyAmount;
import finance.trove.subscriptions.dto.SubscriptionCurrencyTotals;
import finance.trove.subscriptions.dto.SubscriptionResponse;
import finance.trove.subscriptions.dto.SubscriptionSummaryResponse;
import finance.trove.subscriptions.dto.UpcomingCharge;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member-facing operations on detected subscriptions
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.8).
 *
 * <p>Ownership is 404-not-403 by construction: every read and write goes through
 * {@link SubscriptionRepository}'s household-scoped finders, inherited from
 * {@code UserScopedRepository}, so an id belonging to another household simply is not found and
 * the refusal never confirms that it exists (§9 risk 2).
 *
 * <p>Nothing here creates or edits a subscription. Rows are written only by detection; the only
 * change a member can make is {@link #cancel} — a soft delete.
 */
@Service
public class SubscriptionService {

    private final SubscriptionRepository subscriptions;
    private final UserRepository users;
    private final UserClock clock;
    private final SubscriptionDetectionProperties properties;

    public SubscriptionService(SubscriptionRepository subscriptions, UserRepository users,
                               UserClock clock, SubscriptionDetectionProperties properties) {
        this.subscriptions = subscriptions;
        this.users = users;
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * The household's active subscriptions, alphabetically: not dismissed, and still charging
     * (see {@link DetectedSubscription#hasStopped}) by the caller's own today.
     */
    @Transactional(readOnly = true)
    public List<DetectedSubscription> listActive(UUID householdId, UUID userId) {
        LocalDate today = clock.today(userId);
        return subscriptions.findAllByHouseholdIdAndCancelledFalseOrderByMerchantNameAsc(
            householdId).stream().filter(subscription -> !subscription.hasStopped(today)).toList();
    }

    /**
     * One subscription of this household — including a CANCELLED one, which stays readable by id
     * after the soft delete.
     */
    @Transactional(readOnly = true)
    public DetectedSubscription get(UUID householdId, UUID id) {
        return subscriptions.findByIdAndHouseholdId(id, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Subscription", id));
    }

    /**
     * Dismisses a subscription: sets {@code is_cancelled} and leaves the row in place.
     *
     * <p>Idempotent — dismissing an already-cancelled subscription still matches its row and still
     * succeeds, so a client that retries does not get a 404 for a request that already worked.
     */
    @Transactional
    public void cancel(UUID householdId, UUID id) {
        if (subscriptions.cancelByIdAndHouseholdId(id, householdId, Instants.now()) == 0) {
            throw ResourceNotFoundException.of("Subscription", id);
        }
    }

    /**
     * The household's aggregate, read for one member.
     *
     * <p>{@code currency} is that member's own {@code users.currency} and nothing else — never a
     * literal (finding F1), and never guessed from the rows, which may be in a currency the member
     * does not read figures in.
     *
     * <p>The upcoming window is counted from the CALLER's today, so two household mates in
     * different timezones can briefly disagree about whether a charge is "in the next fourteen
     * days". That is the right trade for a read: the window is a display convenience, whereas
     * {@code nextChargeDate} itself is a stored fact about the household and is computed once, in
     * the household's own zone.
     */
    @Transactional(readOnly = true)
    public SubscriptionSummaryResponse summary(UUID householdId, UUID userId) {
        List<DetectedSubscription> active = listActive(householdId, userId);
        CurrencyCode currency = users.findByIdAndHouseholdId(userId, householdId)
            .map(User::getCurrency)
            .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        int flagged = (int) active.stream()
            .filter(subscription -> subscription.getSignal() != SubscriptionSignal.GREEN).count();

        return new SubscriptionSummaryResponse(currency, active.size(), flagged,
            totalsByCurrency(active), upcoming(active, clock.today(userId)));
    }

    /**
     * One entry per currency actually present, ordered by currency code.
     *
     * <p>Each subscription is normalised to a month with the exact fraction on its own
     * {@code BillingCycle} — 52/12, 26/12, 1, 1/3, 1/12 — instead of the source's rounded
     * {@code 4.33} and {@code 2.17} multipliers, and summed through {@link Money}, which refuses
     * to add across currencies at runtime rather than producing a plausible wrong number.
     */
    private static List<SubscriptionCurrencyTotals> totalsByCurrency(
        List<DetectedSubscription> active) {

        Map<CurrencyCode, Money> runRates = new EnumMap<>(CurrencyCode.class);
        Map<CurrencyCode, int[]> counts = new EnumMap<>(CurrencyCode.class);
        for (DetectedSubscription subscription : active) {
            CurrencyCode currency = subscription.getCurrency();
            runRates.merge(currency, monthlyEquivalent(subscription), Money::add);
            int[] tally = counts.computeIfAbsent(currency, key -> new int[2]);
            tally[0]++;
            if (subscription.getSignal() != SubscriptionSignal.GREEN) {
                tally[1]++;
            }
        }
        List<SubscriptionCurrencyTotals> totals = new ArrayList<>();
        runRates.forEach((currency, runRate) -> totals.add(new SubscriptionCurrencyTotals(
            currency, runRate.amount(), counts.get(currency)[0], counts.get(currency)[1])));
        return totals;
    }

    /** What one subscription costs per month, in its own currency. */
    private static Money monthlyEquivalent(DetectedSubscription subscription) {
        Money amount = new Money(subscription.getNormalizedAmount(), subscription.getCurrency());
        return amount.multiply(subscription.getBillingCycle().monthlyNumerator())
            .divide(subscription.getBillingCycle().monthlyDenominator());
    }

    /**
     * Charges projected inside the upcoming window, grouped by date.
     *
     * <p>A projected date already in the PAST is excluded rather than shown as due today: it means
     * the charge was expected and has not been observed, which the row reports with its
     * {@code RED} signal. Putting it in an "upcoming" list would tell the member money is about to
     * leave their account when what actually happened is that it did not.
     */
    private List<UpcomingCharge> upcoming(List<DetectedSubscription> active, LocalDate today) {
        LocalDate cutoff = today.plusDays(properties.upcomingDays());
        Map<LocalDate, List<DetectedSubscription>> byDate = new TreeMap<>();
        for (DetectedSubscription subscription : active) {
            LocalDate due = subscription.getNextChargeDate();
            if (due == null || due.isBefore(today) || due.isAfter(cutoff)) {
                continue;
            }
            byDate.computeIfAbsent(due, key -> new ArrayList<>()).add(subscription);
        }

        List<UpcomingCharge> charges = new ArrayList<>();
        byDate.forEach((date, due) -> charges.add(new UpcomingCharge(date,
            due.stream().map(SubscriptionResponse::of).toList(), dayTotals(due))));
        return charges;
    }

    /** One day's total per currency present — never one blended figure. */
    private static List<SubscriptionCurrencyAmount> dayTotals(List<DetectedSubscription> due) {
        Map<CurrencyCode, Money> totals = new LinkedHashMap<>();
        for (DetectedSubscription subscription : due) {
            totals.merge(subscription.getCurrency(),
                new Money(subscription.getNormalizedAmount(), subscription.getCurrency()),
                Money::add);
        }
        List<SubscriptionCurrencyAmount> amounts = new ArrayList<>();
        totals.forEach((currency, total) ->
            amounts.add(new SubscriptionCurrencyAmount(currency, total.amount())));
        amounts.sort((left, right) -> left.currency().compareTo(right.currency()));
        return amounts;
    }
}
