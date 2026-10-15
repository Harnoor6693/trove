package finance.trove.budgets.rollover;

import finance.trove.budgets.envelope.Envelope;
import finance.trove.budgets.envelope.EnvelopeRepository;
import finance.trove.budgets.envelope.EnvelopeSpendService;
import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetPeriodRepository;
import finance.trove.common.time.UserClock;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carries a finished {@code ROLLOVER} period's unspent balances into the following month
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5, §5.6; {@code docs/recovery/07-READINESS-GAPS.md}
 * P2-39).
 *
 * <h2>The arithmetic, and what was wrong with it</h2>
 * {@code unspent = allocated + rolloverAmount - spent}, floored at zero — unchanged from the
 * pre-collapse service, whose own tests were honest about it. What was wrong was the input: nothing
 * in the platform ever wrote {@code spent} (finding D1), so {@code unspent} was always the FULL
 * allocation and every month's carry compounded on the last one without bound. The arithmetic only
 * means what it says now that {@link EnvelopeSpendService} exists.
 *
 * <h2>The floor forgives overspend, deliberately</h2>
 * An envelope that spent more than it had carries nothing forward rather than carrying a debt.
 * Gap P2-39 names this as "silently forgiving overspend" and proposes a per-budget overspend
 * policy; that is a product decision, is SHOULD-phase, and is deferred here rather than invented.
 * The behaviour is pinned by a named test so that a later step changes it on purpose or not at all.
 *
 * <h2>Idempotent and catch-up safe</h2>
 * Each source period is marked with {@link BudgetPeriod#markRolledOver} in the same transaction
 * that writes its carries, and the job only ever selects periods where that marker is null. So a
 * second pass in the same window finds nothing to do, and a container that was down for a week
 * still finds every missed month waiting — the decision comes from the DATA, not from asking
 * whether today happens to be the first of the month, which is exactly what the source's monthly
 * cron did.
 */
@Service
public class RolloverService {

    private static final Logger LOG = LoggerFactory.getLogger(RolloverService.class);

    private final BudgetPeriodRepository periods;
    private final EnvelopeRepository envelopes;
    private final EnvelopeSpendService spend;
    private final UserClock clock;

    public RolloverService(BudgetPeriodRepository periods, EnvelopeRepository envelopes,
                           EnvelopeSpendService spend, UserClock clock) {
        this.periods = periods;
        this.envelopes = envelopes;
        this.spend = spend;
        this.clock = clock;
    }

    /**
     * Carries {@code sourceId}'s unspent balances into the next month and marks it done.
     *
     * <p>{@code REQUIRES_NEW} so that one household's failure — a period whose creating member has
     * an unreadable timezone, a constraint nobody anticipated — does not roll back the households
     * already carried forward in the same pass, which is the shape the source used and the one
     * part of it worth copying verbatim.
     *
     * <p>It takes an id rather than an entity because it opens its own transaction: an entity
     * loaded in the caller's would be detached here.
     *
     * @return the number of envelopes credited; {@code 0} for a period already marked, which is
     *         what a second run in the same window sees
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int rollForward(UUID householdId, UUID sourceId) {
        Optional<BudgetPeriod> found = periods.findByIdAndHouseholdId(sourceId, householdId);
        if (found.isEmpty() || found.get().getRolledOverAt() != null) {
            // Already carried — by a previous run, or by another node that held the lock first.
            return 0;
        }
        BudgetPeriod source = found.get();
        YearMonth targetMonth = source.month().plusMonths(1);

        List<Envelope> sourceEnvelopes = envelopes
            .findAllByHouseholdIdAndPeriodIdOrderByCreatedAtAsc(householdId, source.getId());
        // The carry is the closed month's real remainder, so refresh each envelope's stored
        // `spent` from the ledger before reading it rather than trusting the last write.
        for (Envelope from : sourceEnvelopes) {
            spend.recalculate(householdId, source.getUserId(), from.getCategoryId(),
                source.month());
        }
        Optional<BudgetPeriod> existingTarget =
            periods.findByHouseholdIdAndMonth(householdId, targetMonth.atDay(1));

        if (existingTarget.isEmpty()
            && sourceEnvelopes.stream().noneMatch(from -> unspent(from).signum() > 0)) {
            // Nothing to carry, and next month does not exist yet. Opening an empty period the
            // household never asked for — every month, for every ROLLOVER budget that happened to
            // spend itself out — would fill the budget list with rows nobody created.
            source.markRolledOver(clock.now());
            periods.save(source);
            return 0;
        }

        BudgetPeriod target = existingTarget.orElseGet(() -> periods.save(
            new BudgetPeriod(source.getHouseholdId(), source.getUserId(), targetMonth,
                source.getTotalIncome(), source.getCurrency(), source.getBudgetType(),
                clock.now())));

        int credited = 0;
        for (Envelope from : sourceEnvelopes) {
            BigDecimal unspent = unspent(from);

            Optional<Envelope> existing =
                envelopes.findByPeriodIdAndCategoryId(target.getId(), from.getCategoryId());
            if (existing.isEmpty() && unspent.signum() == 0) {
                // Nothing to carry and nothing to carry it into. Creating an envelope that
                // budgets zero and carries zero would put a budget line in front of the household
                // that it never asked for and that says nothing.
                continue;
            }

            // Built but not yet saved: the carry is set first so the row reaches the database
            // once, complete, rather than as an insert followed immediately by an update.
            Envelope to = existing.orElseGet(() -> new Envelope(householdId, target.getId(),
                from.getCategoryId(), from.getCurrency().normalise(BigDecimal.ZERO),
                from.getCurrency(), clock.now()));
            // SET, not add: one source period can carry into one target period exactly once
            // (UNIQUE (household_id, month) makes the target unique, and the marker makes the
            // source single-use), so setting is both correct and re-runnable.
            to.setRolloverAmount(unspent);
            envelopes.save(to);
            credited++;

            // The new month's envelope starts with whatever the ledger already says about it —
            // a household that spent on the 1st before the job ran must not see zero.
            spend.recalculate(householdId, source.getUserId(), from.getCategoryId(), targetMonth);
        }

        source.markRolledOver(clock.now());
        periods.save(source);
        LOG.info("envelope-rollover carried {} envelopes from {} into {} for household {}",
            credited, source.month(), targetMonth, householdId);
        return credited;
    }

    /**
     * {@code allocated + rolloverAmount - spent}, floored at zero and quoted at the envelope's own
     * currency scale — the whole of the carry-forward arithmetic, in one place.
     */
    private static BigDecimal unspent(Envelope from) {
        return from.getCurrency().normalise(from.getAllocated()
            .add(from.getRolloverAmount())
            .subtract(from.getSpent())
            .max(BigDecimal.ZERO));
    }
}
