package finance.trove.subscriptions.detection;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.transactions.Transaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns one merchant's charges into at most one {@link SubscriptionCandidate}
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.7, §6 D2).
 *
 * <p>No database, no Spring state, no clock of its own — the caller passes "today" in, so every
 * rule below is testable on a list of amounts and dates.
 *
 * <h2>Two currencies for one merchant</h2>
 * <p>A merchant billed in both CAD and USD is not one subscription with an averaged amount:
 * {@code Money} refuses to add across currencies, and the row has a single {@code currency}
 * column. Keying the table on {@code (household_id, merchant_name, currency)} would need a schema
 * this step does not have, so the rule is stated instead of dodged: the charges are partitioned by
 * currency, each partition is classified INDEPENDENTLY, and if more than one produces a candidate
 * the one whose most recent charge is NEWEST wins — the currency the member is being billed in
 * now. Ties break on the currency's own name so a scan is deterministic.
 *
 * <p>The consequence, stated plainly because a client will meet it: a household that genuinely
 * pays the same merchant in two currencies sees only the current one. It is the honest answer
 * available without a schema change, and it is recorded in the step's open issues.
 */
@Component
public class CandidateFinder {

    private final IntervalClassifier classifier;
    private final ConfidenceCalculator confidence;
    private final SubscriptionDetectionProperties properties;

    public CandidateFinder(IntervalClassifier classifier, ConfidenceCalculator confidence,
                           SubscriptionDetectionProperties properties) {
        this.classifier = classifier;
        this.confidence = confidence;
        this.properties = properties;
    }

    /**
     * The best candidate for one merchant's charges, across every currency they were made in.
     *
     * @param merchantName the normalised merchant name
     * @param charges      that merchant's DEBIT transactions inside the window, oldest first
     * @param today        the household's own today, from {@code UserClock}
     * @return the candidate, or empty when these charges are not a subscription
     */
    public Optional<SubscriptionCandidate> find(String merchantName, List<Transaction> charges,
                                                LocalDate today) {
        Map<CurrencyCode, List<Transaction>> byCurrency = new EnumMap<>(CurrencyCode.class);
        for (Transaction charge : charges) {
            byCurrency.computeIfAbsent(charge.getCurrency(), key -> new ArrayList<>()).add(charge);
        }

        List<SubscriptionCandidate> candidates = new ArrayList<>();
        byCurrency.forEach((currency, sameCurrency) ->
            inOneCurrency(merchantName, currency, sameCurrency, today).ifPresent(candidates::add));

        return candidates.stream().max(
            Comparator.comparing(SubscriptionCandidate::lastSeenDate)
                .thenComparing(candidate -> candidate.currency().name()));
    }

    /** The whole rule, for charges that are all in one currency. */
    private Optional<SubscriptionCandidate> inOneCurrency(String merchantName,
                                                          CurrencyCode currency,
                                                          List<Transaction> charges,
                                                          LocalDate today) {
        List<Transaction> sorted = new ArrayList<>(charges);
        sorted.sort(Comparator.comparing(Transaction::getDate)
            .thenComparing(Transaction::getId, Comparator.comparing(UUID::toString)));

        List<LocalDate> dates = sorted.stream().map(Transaction::getDate).toList();
        List<BigDecimal> amounts = sorted.stream().map(Transaction::getAmount).toList();

        List<Integer> gaps = new ArrayList<>();
        for (int i = 1; i < dates.size(); i++) {
            gaps.add((int) (dates.get(i).toEpochDay() - dates.get(i - 1).toEpochDay()));
        }

        Optional<BillingCycle> cycle = classifier.classify(gaps);
        if (cycle.isEmpty()) {
            return Optional.empty();
        }

        AmountLevels levels = AmountLevels.of(amounts, properties.levelTolerancePercent(),
            currency.minorUnits());
        if (!levels.isSubscriptionLike()) {
            return Optional.empty();
        }

        BigDecimal score = confidence.calculate(dates, amounts, cycle.get());
        if (score.compareTo(properties.confidenceThreshold()) < 0) {
            return Optional.empty();
        }

        LocalDate lastSeen = dates.getLast();
        LocalDate nextCharge = cycle.get().advance(lastSeen);
        // Overdue is measured against the cadence's OWN tolerance, so a gap the classifier would
        // still accept as this cadence can never simultaneously count as a missing charge.
        boolean overdue =
            nextCharge.isBefore(today.minusDays(properties.toleranceDays(cycle.get())));

        return Optional.of(new SubscriptionCandidate(merchantName, currency,
            levels.currentAmount(), cycle.get(), nextCharge, overdue, dates.getFirst(), lastSeen,
            mostRecentCategoryId(sorted), score,
            sorted.stream().map(Transaction::getId).toList()));
    }

    /**
     * The category of the most recent charge that has one.
     *
     * <p>Most recent rather than first: a member who recategorises a subscription is telling the
     * detector what it is now, and the source took the OLDEST transaction's category, which meant
     * a correction never took effect.
     */
    private static UUID mostRecentCategoryId(List<Transaction> sortedOldestFirst) {
        for (int i = sortedOldestFirst.size() - 1; i >= 0; i--) {
            UUID categoryId = sortedOldestFirst.get(i).getCategoryId();
            if (categoryId != null) {
                return categoryId;
            }
        }
        return null;
    }
}
