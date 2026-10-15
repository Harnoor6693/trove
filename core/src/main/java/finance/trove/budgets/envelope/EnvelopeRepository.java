package finance.trove.budgets.envelope;

import finance.trove.common.repository.UserScopedRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link Envelope} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5, §6 D1).
 *
 * <p>Extends {@link UserScopedRepository}: no inherited {@code findById}, no {@code findAll},
 * and every query below names its household (§9 risk 2).
 */
public interface EnvelopeRepository extends UserScopedRepository<Envelope, UUID> {

    /** The envelopes of one period, in a stable order. */
    List<Envelope> findAllByHouseholdIdAndPeriodIdOrderByCreatedAtAsc(UUID householdId,
                                                                      UUID periodId);

    /**
     * One envelope, resolved by BOTH its own id and the period named in the path.
     *
     * <p>The period is part of the lookup rather than checked afterwards: an envelope id paired
     * with somebody else's period id then simply is not found, which is a 404 by construction
     * instead of a 403 that would confirm the row exists.
     */
    Optional<Envelope> findByIdAndHouseholdIdAndPeriodId(UUID id, UUID householdId, UUID periodId);

    /** Whether this period already budgets this category — the uniqueness pre-check. */
    boolean existsByPeriodIdAndCategoryId(UUID periodId, UUID categoryId);

    /** Whether this period budgets anything at all — the ZERO_BASED invariant's precondition. */
    boolean existsByPeriodId(UUID periodId);

    /** The envelope of one category inside one period, the rollover job's target lookup. */
    Optional<Envelope> findByPeriodIdAndCategoryId(UUID periodId, UUID categoryId);

    /** Whether any envelope anywhere budgets this category — used by tests, not by a route. */
    boolean existsByCategoryId(UUID categoryId);

    /**
     * The envelope {@code EnvelopeSpendService} rewrites: one household's envelope for one
     * category in one month.
     *
     * <p>The month is reached through the period rather than stored on the envelope, so there is
     * one place a period's month is recorded and no chance of the two disagreeing after an edit.
     * {@code UNIQUE (household_id, month)} on {@code budget_period} and
     * {@code UNIQUE (period_id, category_id)} here together make this at most one row.
     */
    @Query("select e from Envelope e, finance.trove.budgets.period.BudgetPeriod p "
        + "where e.periodId = p.id and e.householdId = :householdId "
        + "and e.categoryId = :categoryId and p.month = :month")
    Optional<Envelope> findForCategoryAndMonth(@Param("householdId") UUID householdId,
                                               @Param("categoryId") UUID categoryId,
                                               @Param("month") LocalDate month);

    /**
     * The sum of a period's allocations — what the {@code ZERO_BASED} invariant is checked
     * against.
     *
     * <p>Returns {@code 0} rather than {@code null} for a period with no envelopes, so the caller
     * never has to distinguish "no rows" from "sums to nothing".
     */
    @Query("select coalesce(sum(e.allocated), 0) from Envelope e where e.periodId = :periodId")
    BigDecimal sumAllocatedByPeriodId(@Param("periodId") UUID periodId);

    /**
     * The same sum with one envelope left out — the form both envelope writes actually need.
     *
     * <p>A create checks "everything already there, plus what I am adding"; an update checks
     * "everything except me, plus my new value". Expressing the exclusion in SQL rather than
     * subtracting the old value in Java means the check reads the same committed state it is
     * about to be measured against.
     */
    @Query("select coalesce(sum(e.allocated), 0) from Envelope e where e.periodId = :periodId "
        + "and e.id <> :excludedId")
    BigDecimal sumAllocatedByPeriodIdExcluding(@Param("periodId") UUID periodId,
                                               @Param("excludedId") UUID excludedId);
}
