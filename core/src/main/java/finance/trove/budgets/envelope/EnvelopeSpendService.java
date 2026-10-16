package finance.trove.budgets.envelope;

import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetPeriodRepository;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import finance.trove.common.events.BudgetThresholdCrossedEvent;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code envelope.spent} from the household's ledger — the writer finding D1 says never
 * existed ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §2.5, §6 "D1 — budgets spent";
 * {@code docs/recovery/01-FINDINGS.md} D1; §9 risk 4).
 *
 * <p>This is one of the five named service interfaces §1.3 lets other packages talk through, so it
 * is designed for its callers rather than for itself: two event listeners in this package, the
 * envelope create path, and the rollover job all reach the same method.
 *
 * <h2>What counts as spending</h2>
 * {@code DEBIT} less {@code REFUND}. Not "the sum of debits": {@code transaction.type} spans all
 * seven values of {@code TransactionType} since V19, and a refund typed {@code REFUND} that did
 * not net down would leave a household unable to reconcile a returned purchase against its own
 * statement. This is deliberately the identical rule {@code DashboardService} states under its own
 * "What counts as spending" heading — two screens disagreeing about whether a refund reduces
 * spending is worse than either answer alone, so the rule is written once and matched here.
 * {@code FEE}, {@code INTEREST}, {@code CREDIT} and {@code OTHER} are not discretionary spending
 * and contribute nothing.
 *
 * <h2>Transfer legs are excluded, and not by this class</h2>
 * The read goes through {@link TransactionQueryService#findSpendableInRange}, whose javadoc names
 * this step as a caller precisely so the exclusion is <em>inherited</em> rather than re-derived
 * (gap P2-14). Paying a credit card from chequing produces a DEBIT and a CREDIT between two
 * accounts the household already owns; counting the debit would inflate every envelope it lands in.
 *
 * <h2>Why a rewrite and never an increment</h2>
 * {@link Envelope#rewriteSpent} replaces the figure with the aggregate over the whole month. That
 * is what makes recategorisation and re-sync safe: moving a transaction from Groceries to
 * Restaurants recomputes both categories from scratch, and re-importing the same file recomputes
 * the same number. An incrementing writer would need to know exactly which rows it had already
 * seen — state nobody keeps — and §9 risk 4 exists because that state is exactly what drifts.
 *
 * <h2>Currency</h2>
 * An envelope sums only transactions of <em>its own</em> currency. A household budgeting one
 * category in CAD and another in USD in the same month is a normal case (owner decision 3: CA and
 * US are both first-class), and there is no rate anywhere in this system with which a USD
 * transaction could be counted against a CAD envelope.
 *
 * <h2>Months are calendar months, not instants</h2>
 * {@code transaction.date} is already a calendar date in the source's own terms — the same
 * reasoning {@code TransactionService.publishCategorised} states when it derives the event's month
 * from {@code YearMonth.from(transaction.getDate())}. So the window here is
 * {@code [month.atDay(1), month.atEndOfMonth()]} and no timezone conversion is involved or wanted.
 * {@code UserClock} decides which month is <em>current</em> for a caller or a job; it does not
 * decide which month a dated row belongs to.
 */
@Service
public class EnvelopeSpendService {

    private static final Logger LOG = LoggerFactory.getLogger(EnvelopeSpendService.class);

    private final EnvelopeRepository envelopes;
    private final BudgetPeriodRepository periods;
    private final TransactionQueryService transactions;
    private final ApplicationEventPublisher events;
    private final HouseholdAlertThresholds alertThresholds;

    /**
     * <p><strong>The alert threshold is per member since collapse step 11.</strong> This
     * constructor used to take one {@code BigDecimal} from
     * {@code trove.budgets.alert-threshold:0.80}, a placeholder chosen because
     * {@code notification_preference} did not exist yet. It does now (V31), so the ratio comes
     * from {@link HouseholdAlertThresholds} — every distinct share the household's members have
     * asked to be told at, rather than one number for everybody. See that interface for why it is
     * a set.
     */
    public EnvelopeSpendService(EnvelopeRepository envelopes, BudgetPeriodRepository periods,
                                TransactionQueryService transactions,
                                ApplicationEventPublisher events,
                                HouseholdAlertThresholds alertThresholds) {
        this.envelopes = envelopes;
        this.periods = periods;
        this.transactions = transactions;
        this.events = events;
        this.alertThresholds = alertThresholds;
    }

    /**
     * The seam §1.3 names: recompute one household's envelope for one category and month.
     *
     * <p>The acting member is taken from the period's {@code user_id} (who opened it) when the
     * caller has no
     * better answer — a scheduled recomputation has no request behind it, and
     * {@link BudgetThresholdCrossedEvent} needs a member to address. If that column is null too
     * (the member has since been deleted) the aggregate is still rewritten and only the alert is
     * skipped: a silent number is a bug, a missing notification is not.
     *
     * @param householdId the ledger
     * @param categoryId  the category whose envelope moves; {@code null} is a no-op, because an
     *                    uncategorised transaction belongs to no envelope
     * @param month       the month, as the transaction's own date reads it
     */
    @Transactional
    public void recalculate(UUID householdId, UUID categoryId, YearMonth month) {
        if (categoryId == null) {
            return;
        }
        recalculateAll(householdId, null, List.of(new CategoryMonth(categoryId, month)));
    }

    /**
     * The same recomputation with a known acting member — the form the envelope create path and
     * the rollover job use, so the alert names whoever actually moved the envelope (the ingest and
     * spend listeners call {@code recalculateAll} with the event's user for the same reason).
     */
    @Transactional
    public void recalculate(UUID householdId, UUID actingUserId, UUID categoryId,
                            YearMonth month) {
        if (categoryId == null) {
            return;
        }
        recalculateAll(householdId, actingUserId,
            List.of(new CategoryMonth(categoryId, month)));
    }

    /**
     * Recomputes a batch of (category, month) pairs, reading the ledger once per distinct month.
     *
     * <p>A sync that lands three hundred rows across two months is two queries and however many
     * envelopes actually exist, not three hundred round trips. Grouping is what makes the ingest
     * listener affordable; the arithmetic is identical either way, because each pair's answer
     * depends only on that month's rows.
     *
     * @param targets deduplicated by value — {@link CategoryMonth} is a record for that reason
     */
    @Transactional
    public void recalculateAll(UUID householdId, UUID actingUserId,
                               Collection<CategoryMonth> targets) {
        // Read once per call rather than once per envelope: a sync that lands three hundred rows
        // recomputes many envelopes of one household, and every one of them is compared against
        // the same set of member thresholds.
        Set<BigDecimal> thresholds = alertThresholds.fractionsFor(householdId);
        Map<YearMonth, List<Transaction>> ledgerByMonth = new LinkedHashMap<>();
        // No null-category guard here: CategoryMonth's compact constructor rejects a null
        // categoryId, so an uncategorised row is turned away by the two recalculate overloads
        // before a target is ever built. A check at this point would be unreachable.
        for (CategoryMonth target : Set.copyOf(targets)) {
            Optional<Envelope> found = envelopes.findForCategoryAndMonth(householdId,
                target.categoryId(), target.month().atDay(1));
            if (found.isEmpty()) {
                // No envelope budgets this category this month. Nothing to write, and creating
                // one would invent a budget line the household never asked for.
                continue;
            }
            Envelope envelope = found.get();
            List<Transaction> rows = ledgerByMonth.computeIfAbsent(target.month(),
                month -> transactions.findSpendableInRange(householdId, month.atDay(1),
                    month.atEndOfMonth()));
            rewrite(envelope, rows, actingUserId, target.month(), thresholds);
        }
    }

    /** Rewrites one envelope from the month's rows and publishes an alert if this crossed it. */
    private void rewrite(Envelope envelope, List<Transaction> monthRows, UUID actingUserId,
                         YearMonth month, Set<BigDecimal> thresholds) {
        CurrencyCode currency = envelope.getCurrency();
        BigDecimal recomputed = currency.normalise(sumSpend(monthRows, envelope));
        BigDecimal previous = envelope.getSpent();
        if (recomputed.compareTo(previous) == 0) {
            // Identical answer: no write, no event. This is the common case on a re-run and it is
            // also what keeps a second pass of the rollover job from re-alerting a whole household.
            return;
        }

        envelope.rewriteSpent(recomputed);
        envelopes.save(envelope);
        LOG.debug("envelope {} spent rewritten {} -> {}", envelope.getId(), previous, recomputed);

        if (crossedAnyThreshold(previous, recomputed, budgetOf(envelope), thresholds)) {
            publishCrossing(envelope, actingUserId, month, recomputed);
        }
    }

    /**
     * {@code DEBIT} less {@code REFUND} over the rows of this envelope's category and currency.
     *
     * <p>The rows have already had transfer legs removed by the query; the two filters here are
     * the ones the query cannot apply, because it serves every aggregate in the product rather
     * than this one.
     */
    private static BigDecimal sumSpend(List<Transaction> monthRows, Envelope envelope) {
        BigDecimal total = BigDecimal.ZERO;
        for (Transaction row : monthRows) {
            if (!envelope.getCategoryId().equals(row.getCategoryId())
                || row.getCurrency() != envelope.getCurrency()) {
                continue;
            }
            switch (row.getType()) {
                case DEBIT -> total = total.add(row.getAmount());
                case REFUND -> total = total.subtract(row.getAmount());
                default -> {
                    // CREDIT is income, and FEE/INTEREST/OTHER are real movements that are not a
                    // household's discretionary spending. TRANSFER cannot reach here at all: the
                    // query excludes paired legs, and an unpaired one is not spending either.
                }
            }
        }
        return total;
    }

    /** Allocated plus carry-over: the amount thresholds and health are measured against. */
    private static BigDecimal budgetOf(Envelope envelope) {
        return envelope.getAllocated().add(envelope.getRolloverAmount());
    }

    /**
     * Whether this write is the moment the envelope went from under one of the household's alert
     * thresholds to at or over it.
     *
     * <p>On the crossing, not on every recalculation — {@link BudgetThresholdCrossedEvent}'s own
     * javadoc says so, and the reason is concrete: a sync that ingests forty grocery transactions
     * recomputes this envelope once per batch, and a member must not receive forty alerts for one
     * afternoon's shopping. Comparing the previous stored figure with the new one is enough state
     * to answer it; nothing extra is persisted.
     *
     * <p><strong>Any of the household's thresholds, not one.</strong> Members hold their own
     * ratios in {@code notification_preference} (V31), and a crossing is only ever detected once —
     * so watching a single ratio would mean a member whose own is higher never hears at all. Each
     * crossing publishes; {@code NotificationEventListener} decides which members it concerns, and
     * V32's per-(user, type, key) unique index means nobody is told twice about one envelope in
     * one month however many of these are published.
     *
     * <p>An envelope with nothing allocated never crosses: there is no ratio, and an alert saying
     * spending is 100 % of a zero budget tells the household nothing it can act on.
     */
    private static boolean crossedAnyThreshold(BigDecimal previous, BigDecimal recomputed,
                                               BigDecimal allocated,
                                               Set<BigDecimal> thresholds) {
        if (allocated.signum() <= 0) {
            return false;
        }
        for (BigDecimal threshold : thresholds) {
            BigDecimal trigger = allocated.multiply(threshold);
            if (previous.compareTo(trigger) < 0 && recomputed.compareTo(trigger) >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Publishes the crossing. {@code NotificationEventListener} turns it into inbox rows and
     * {@code WebhookEventListener} into webhook deliveries.
     */
    private void publishCrossing(Envelope envelope, UUID actingUserId, YearMonth month,
                                 BigDecimal recomputed) {
        UUID member = actingUserId != null ? actingUserId
            : periods.findByIdAndHouseholdId(envelope.getPeriodId(), envelope.getHouseholdId())
                .map(BudgetPeriod::getUserId).orElse(null);
        if (member == null) {
            LOG.debug("envelope {} crossed its alert threshold with no member to address; the "
                + "aggregate is written, the alert is skipped", envelope.getId());
            return;
        }
        CurrencyCode currency = envelope.getCurrency();
        events.publishEvent(new BudgetThresholdCrossedEvent(envelope.getHouseholdId(), member,
            envelope.getId(), envelope.getCategoryId(), month, new Money(recomputed, currency),
            new Money(budgetOf(envelope), currency)));
    }
}
