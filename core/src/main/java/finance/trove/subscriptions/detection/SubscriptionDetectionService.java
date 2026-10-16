package finance.trove.subscriptions.detection;

import finance.trove.categorisation.MerchantNormaliser;
import finance.trove.common.domain.Money;
import finance.trove.common.events.SubscriptionPriceChangedEvent;
import finance.trove.common.time.Instants;
import finance.trove.common.time.UserClock;
import finance.trove.subscriptions.alert.AlertType;
import finance.trove.subscriptions.alert.SubscriptionAlert;
import finance.trove.subscriptions.alert.SubscriptionAlertRepository;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds a household's recurring charges and writes them down
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.7, §6 "D2 — subscription scan").
 *
 * <h2>The algorithm</h2>
 * <p>The pre-collapse application class carried an unusually accurate description of this, and it
 * is kept here because it is still true of the parts that did not change:
 * <ol>
 *   <li>read the household's DEBIT transactions of the last 25 months;</li>
 *   <li>group them by normalised merchant name, skipping merchants with too few charges;</li>
 *   <li>sort by date and compute the gaps between consecutive charges;</li>
 *   <li>classify the gaps into a {@link BillingCycle} — at least 70% must fall inside one
 *       cadence's tolerance window;</li>
 *   <li>check the amounts are a subscription's amounts rather than variable spending;</li>
 *   <li>score the candidate and discard it below the confidence threshold;</li>
 *   <li>upsert the row with the current price, the cadence, the last-seen date and the projected
 *       next charge;</li>
 *   <li>when the price changed, publish
 *       {@link SubscriptionPriceChangedEvent}, and for an increase also raise a
 *       {@code PRICE_INCREASE} alert.</li>
 * </ol>
 *
 * <h2>What changed, and why each change was necessary</h2>
 * <ul>
 *   <li><strong>The read is in-process.</strong> Steps 1 and 2 went over HTTP to
 *       ingestion-service through {@code IngestionClient} — a {@code RestClient} with no timeouts
 *       at all (SD-4) pointed at a hostname that did not exist under the compose file the README
 *       named (SD-2). That client, its pagination loop, its shared service token and
 *       {@code TransactionDto} are DELETED rather than moved: the read is now
 *       {@link TransactionQueryService#debitsSince}, which is already DEBIT-only and already
 *       excludes transfer legs (gap P2-14), so this step inherits the exclusion instead of
 *       re-deriving it and disagreeing with the dashboard.</li>
 *   <li><strong>Scoped by household.</strong> Every query and every row is
 *       {@code household_id} (§1.5). The source scoped by {@code user_id}, which on a two-member
 *       install hid each member's subscriptions from the other while they paid the same bills.</li>
 *   <li><strong>25 months, not ninety days</strong> (§6 D2, CI-45) — long enough to hold the
 *       three charges the classifier needs from an ANNUAL subscription.</li>
 *   <li><strong>One normaliser.</strong> Grouping uses
 *       {@link MerchantNormaliser#normalise(String)}, the categorisation package's, not this
 *       module's own {@code MerchantNameNormalizer}. Two normalisers in one JVM means the same
 *       merchant becomes two subscriptions on one screen and one on another.</li>
 *   <li><strong>No floating point.</strong> The source did every amount comparison, the
 *       coefficient of variation and the confidence score in {@code double} (finding F3). Every
 *       one of them is {@link BigDecimal} here.</li>
 *   <li><strong>An upsert, not a check-then-act</strong> — see {@link SubscriptionUpsert}.</li>
 * </ul>
 *
 * <h2>Three triggers, one method</h2>
 * <p>{@link #detectForHousehold(UUID)} is called by the nightly {@code subscription-scan} job, by
 * {@code POST /v1/subscriptions/scan}, and by the listener on ingested transactions. They differ
 * only in what makes them run: there is one implementation of "what a scan does", so a household
 * cannot be scanned two subtly different ways.
 */
@Service
public class SubscriptionDetectionService {

    private static final Logger LOG = LoggerFactory.getLogger(SubscriptionDetectionService.class);

    private final TransactionQueryService transactions;
    private final CandidateFinder candidates;
    private final SubscriptionUpsert upsert;
    private final SubscriptionAlertRepository alerts;
    private final UserRepository users;
    private final UserClock clock;
    private final ApplicationEventPublisher events;
    private final SubscriptionDetectionProperties properties;

    public SubscriptionDetectionService(TransactionQueryService transactions,
                                        CandidateFinder candidates, SubscriptionUpsert upsert,
                                        SubscriptionAlertRepository alerts, UserRepository users,
                                        UserClock clock, ApplicationEventPublisher events,
                                        SubscriptionDetectionProperties properties) {
        this.transactions = transactions;
        this.candidates = candidates;
        this.upsert = upsert;
        this.alerts = alerts;
        this.users = users;
        this.clock = clock;
        this.events = events;
        this.properties = properties;
    }

    /**
     * Scans one household. The whole feature runs through here.
     *
     * <p>Idempotent by construction: over unchanged history a second run recomputes the same
     * cadence, the same price and the same projection, the upsert's {@code IS DISTINCT FROM}
     * comparisons all come out false, no column moves, no alert is written and no event is
     * published.
     *
     * @param householdId the ledger to scan
     * @return how many subscriptions were written or refreshed
     */
    @Transactional
    public int detectForHousehold(UUID householdId) {
        UUID attributedTo = attributionUserId(householdId);
        if (attributedTo == null) {
            // A household with no members has no timezone to compute "today" in and nobody to
            // attribute the write to. Nothing to do, and not an error.
            return 0;
        }

        LocalDate today = clock.today(attributedTo);
        LocalDate since = today.minus(properties.detectionWindow());
        Map<String, List<Transaction>> byMerchant =
            groupByMerchant(transactions.debitsSince(householdId, since));

        int detected = 0;
        for (Map.Entry<String, List<Transaction>> entry : byMerchant.entrySet()) {
            if (entry.getValue().size() < properties.minimumCharges()) {
                continue;
            }
            Optional<SubscriptionCandidate> candidate =
                candidates.find(entry.getKey(), entry.getValue(), today);
            if (candidate.isEmpty()) {
                continue;
            }
            record(householdId, attributedTo, candidate.get());
            detected++;
        }
        LOG.debug("subscription scan: household {} produced {} subscription(s) from {} merchant(s)",
            householdId, detected, byMerchant.size());
        return detected;
    }

    /** Writes one candidate, its transaction links, and anything its price change implies. */
    private void record(UUID householdId, UUID attributedTo, SubscriptionCandidate candidate) {
        SubscriptionUpsert.Outcome outcome = upsert.apply(householdId, attributedTo, candidate);
        upsert.linkTransactions(outcome.subscriptionId(), candidate.transactionIds());

        if (!outcome.isComparablePriceChange(candidate.currency(), candidate.amount())) {
            return;
        }
        Money before = new Money(outcome.previousAmount(), candidate.currency());
        Money now = new Money(candidate.amount(), candidate.currency());

        // Published on the CHANGE, not on every scan: `price_changed` is sticky, so a later scan
        // that sees the same price finds nothing to compare and publishes nothing. Consumed
        // by NotificationEventListener (inbox) and WebhookEventListener (webhooks).
        events.publishEvent(new SubscriptionPriceChangedEvent(householdId, attributedTo,
            outcome.subscriptionId(), candidate.merchantName(), before, now));

        if (now.isGreaterThan(before)) {
            // Only an increase writes a row. A decrease still published the event above, whose
            // delta() is signed, but AlertType has no PRICE_DECREASE and a row typed "increase"
            // describing a fall would be a lie.
            alerts.save(new SubscriptionAlert(householdId, attributedTo,
                outcome.subscriptionId(), AlertType.PRICE_INCREASE,
                priceIncreaseMessage(candidate.merchantName(), before, now), Instants.now()));
        }
    }

    /** {@code "SPOTIFY went up from 9.99 CAD to 11.99 CAD."} — no currency literal anywhere. */
    private static String priceIncreaseMessage(String merchantName, Money before, Money now) {
        return merchantName + " went up from " + before + " to " + now + ".";
    }

    /**
     * Groups charges by normalised merchant, in first-seen order so a scan is deterministic.
     *
     * <p>A charge whose merchant name normalises to nothing — a blank, or a string of punctuation
     * — is skipped rather than grouped under the empty key, which would otherwise collect every
     * anonymous debit in the ledger into one bogus "subscription".
     */
    static Map<String, List<Transaction>> groupByMerchant(List<Transaction> charges) {
        Map<String, List<Transaction>> byMerchant = new LinkedHashMap<>();
        for (Transaction charge : charges) {
            String merchant = MerchantNormaliser.normalise(charge.getMerchantName());
            if (merchant.isEmpty()) {
                continue;
            }
            byMerchant.computeIfAbsent(merchant, key -> new ArrayList<>()).add(charge);
        }
        return byMerchant;
    }

    /**
     * The member a scheduled scan is attributed to, and whose timezone decides "today".
     *
     * <p>The household's own administrator, exactly as {@code NetWorthSnapshotService} resolves it
     * and for the same reason: a scan has no caller, and the dates it computes must not depend on
     * which member happened to trigger it — otherwise the nightly job and the manual button would
     * disagree about what day it is, and about which charges are overdue, whenever two members sit
     * in different timezones. The consequence is deliberate: the projection is the household's,
     * not the reader's (§1.5).
     *
     * @return the first ADMIN, else the first member, else {@code null} for an empty household
     */
    private UUID attributionUserId(UUID householdId) {
        List<User> members = users.findAllByHouseholdIdOrderByCreatedAtAsc(householdId);
        return members.stream().filter(member -> member.getRole() == UserRole.ADMIN)
            .map(User::getId).findFirst()
            .orElseGet(() -> members.stream().map(User::getId).findFirst().orElse(null));
    }
}
