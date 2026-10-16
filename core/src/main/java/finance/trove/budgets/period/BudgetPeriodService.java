package finance.trove.budgets.period;

import finance.trove.budgets.envelope.EnvelopeRepository;
import finance.trove.budgets.period.dto.BudgetPeriodRequest;
import finance.trove.budgets.period.dto.BudgetPeriodUpdateRequest;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.time.UserClock;
import finance.trove.users.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over a household's {@link BudgetPeriod}s ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6).
 *
 * <p>Ownership is 404-not-403 by construction: every read and write goes through
 * {@link BudgetPeriodRepository}'s household-scoped finders, so an id belonging to another
 * household simply is not found (§9 risk 2).
 */
@Service
public class BudgetPeriodService {

    private final BudgetPeriodRepository periods;
    private final EnvelopeRepository envelopes;
    private final UserRepository users;
    private final UserClock clock;

    public BudgetPeriodService(BudgetPeriodRepository periods, EnvelopeRepository envelopes,
                               UserRepository users, UserClock clock) {
        this.periods = periods;
        this.envelopes = envelopes;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BudgetPeriod> list(UUID householdId) {
        return periods.findAllByHouseholdIdOrderByMonthDesc(householdId);
    }

    /** One period of this household, or 404. */
    @Transactional(readOnly = true)
    public BudgetPeriod get(UUID householdId, UUID periodId) {
        return periods.findByIdAndHouseholdId(periodId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Budget period", periodId));
    }

    /**
     * The household's period for the month it is <strong>right now where this member is</strong>.
     *
     * <p>{@link UserClock#currentMonth} reads {@code users.timezone}, so two members of one
     * household in Vancouver and Toronto are correctly shown different months for the few hours
     * around a boundary. The pre-collapse {@code BudgetService.java:141} called
     * {@code LocalDate.now()} with no zone against a container that is UTC by policy, so a
     * Vancouver member's month rolled over at 16:00 on the 31st (gap P2-15).
     *
     * <p>A month with no period is a 404, not an empty 200 — §4.6 is explicit, and the source's
     * hand-built {@code ApiResponse.error} body was the one endpoint in that module that did not
     * go through the exception handler.
     */
    @Transactional(readOnly = true)
    public BudgetPeriod current(UUID householdId, UUID userId) {
        YearMonth month = clock.currentMonth(userId);
        return periods.findByHouseholdIdAndMonth(householdId, month.atDay(1))
            .orElseThrow(() -> new ResourceNotFoundException(
                "No budget period for " + month + " in this household"));
    }

    /**
     * Opens a period for a month.
     *
     * <p>The month is normalised to the first before anything else happens, because both the
     * duplicate check and the unique index are only meaningful against a normalised value.
     * A second period for a month the household already has is a 409: under one shared ledger two
     * members must not each own a January (§1.5).
     */
    @Transactional
    public BudgetPeriod create(UUID householdId, UUID userId, BudgetPeriodRequest request) {
        LocalDate month = BudgetPeriod.normaliseMonth(request.month());
        if (periods.findByHouseholdIdAndMonth(householdId, month).isPresent()) {
            throw new ConflictException(
                "This household already has a budget period for " + YearMonth.from(month));
        }

        CurrencyCode currency = request.currency() != null ? request.currency()
            : creatingMemberCurrency(householdId, userId);
        BudgetType type = request.budgetType() != null ? request.budgetType()
            : BudgetType.FLEXIBLE;

        return periods.save(new BudgetPeriod(householdId, userId, YearMonth.from(month),
            currency.normalise(request.totalIncome()), currency, type, clock.now()));
    }

    /**
     * Replaces the two mutable fields.
     *
     * <p>The {@code ZERO_BASED} invariant is re-checked afterwards, which the source never did
     * from this side: it validated envelope writes against {@code totalIncome} but let
     * {@code totalIncome} itself move underneath them, leaving a period that its own rule says is
     * impossible and that the next envelope edit would then refuse for a reason the member did not
     * cause.
     */
    @Transactional
    public BudgetPeriod update(UUID householdId, UUID periodId,
                               BudgetPeriodUpdateRequest request) {
        BudgetPeriod period = get(householdId, periodId);
        period.setTotalIncome(period.getCurrency().normalise(request.totalIncome()));
        period.setBudgetType(request.budgetType());
        if (envelopes.existsByPeriodId(periodId)) {
            // Only once the period has envelopes. One that has none has allocated nothing, and
            // refusing to change its income before its first envelope exists would make
            // ZERO_BASED unusable rather than strict.
            requireZeroBasedInvariant(period, envelopes.sumAllocatedByPeriodId(periodId, period.getCurrency()));
        }
        return periods.save(period);
    }

    /**
     * Deletes the period and, through V24's {@code ON DELETE CASCADE}, its envelopes.
     *
     * <p>Transactions are untouched: an envelope is an aggregate over the ledger, never a part of
     * it, so deleting a budget can never delete a household's financial history.
     */
    @Transactional
    public void delete(UUID householdId, UUID periodId) {
        if (periods.deleteByIdAndHouseholdId(periodId, householdId) == 0) {
            throw ResourceNotFoundException.of("Budget period", periodId);
        }
    }

    /**
     * Holds {@code ZERO_BASED}'s "every dollar allocated" rule.
     *
     * <p><strong>400 {@code validation-error}, not 409 and not 422.</strong> The source answered
     * 422, a status this contract has no slug for at all (handoff §8's slug table). Of the two
     * slugs that do exist, {@code conflict} would be the wrong one: a 409 tells a client to reload
     * and retry, and retrying this body would fail for ever — the caller has to change the number
     * it sent, which is what {@code validation-error} means.
     *
     * <p>A {@code FLEXIBLE} or {@code ROLLOVER} period passes unconditionally. The callers decide
     * <em>when</em> to ask: envelope create and update do, because after either the period
     * certainly has an envelope (delete does not check), and a period update does only once one
     * exists — refusing to change a
     * ZERO_BASED period's income before its first envelope exists would make the type unusable
     * rather than strict.
     *
     * @param allocationSum what the period's allocations will total after the write in progress
     */
    public static void requireZeroBasedInvariant(BudgetPeriod period, BigDecimal allocationSum) {
        if (period.getBudgetType() != BudgetType.ZERO_BASED) {
            return;
        }
        if (allocationSum.compareTo(period.getTotalIncome()) != 0) {
            throw new ValidationException("A ZERO_BASED budget must allocate every dollar of its "
                + "income: the envelopes would total " + allocationSum.toPlainString() + " "
                + period.getCurrency() + " against an income of "
                + period.getTotalIncome().toPlainString() + " " + period.getCurrency());
        }
    }

    private CurrencyCode creatingMemberCurrency(UUID householdId, UUID userId) {
        return users.findByIdAndHouseholdId(userId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId))
            .getCurrency();
    }
}
