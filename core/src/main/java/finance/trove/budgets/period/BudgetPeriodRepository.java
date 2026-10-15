package finance.trove.budgets.period;

import finance.trove.common.repository.UserScopedRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link BudgetPeriod} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5).
 *
 * <p>Extends {@link UserScopedRepository}, so it inherits no {@code findById} and no
 * {@code findAll}: every query below names its household, which is what makes a cross-household
 * read a compile error rather than a code-review question (§9 risk 2, enforced by ArchUnit since
 * {@code 571aa17}).
 */
public interface BudgetPeriodRepository extends UserScopedRepository<BudgetPeriod, UUID> {

    /** The household's periods, newest month first. Twelve rows a year; bounded by construction. */
    List<BudgetPeriod> findAllByHouseholdIdOrderByMonthDesc(UUID householdId);

    /**
     * The household's period for one month, or empty.
     *
     * <p>{@code month} must already be normalised to the first of the month — see
     * {@link BudgetPeriod#normaliseMonth}. Passing the 15th finds nothing, which is why the
     * service normalises before it ever reaches here.
     */
    Optional<BudgetPeriod> findByHouseholdIdAndMonth(UUID householdId, LocalDate month);

    /**
     * The rollover job's work list: {@code ROLLOVER} periods at or before {@code maxMonth} that
     * have not been carried forward yet, oldest first.
     *
     * <p>It takes no {@code householdId} because it is what <em>produces</em> the households the
     * job then works one at a time — the same shape as
     * {@code TransactionRepository.findHouseholdIdsWithUnpairedLegs}, and the reason the ArchUnit
     * rule is about scoped <em>finders</em> rather than about every query having a household
     * parameter. A scheduled job has no caller whose household it could be scoped to.
     *
     * <p>Oldest first matters: a household that was missed for three months is carried forward one
     * month at a time, in order, so each month's carry lands on the period that follows it rather
     * than all three landing on the newest one.
     *
     * <p>{@code maxMonth} is the first day of the current month on the container's UTC clock. It
     * is deliberately generous — a period is only <em>actually</em> due once its month has ended
     * in its own creator's timezone, and the job checks that per row through {@code UserClock}.
     * Filtering here to something tighter would push a per-user boundary into SQL, where the
     * user's zone is not known.
     */
    @Query("select p from BudgetPeriod p where p.budgetType = finance.trove.budgets.period."
        + "BudgetType.ROLLOVER and p.rolledOverAt is null and p.month <= :maxMonth "
        + "order by p.month asc")
    List<BudgetPeriod> findRolloverCandidates(@Param("maxMonth") LocalDate maxMonth);
}
