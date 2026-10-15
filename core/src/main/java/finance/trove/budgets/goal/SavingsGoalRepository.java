package finance.trove.budgets.goal;

import finance.trove.common.repository.UserScopedRepository;
import java.util.List;
import java.util.UUID;

/**
 * Repository over {@link SavingsGoal} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5).
 *
 * <p>Extends {@link UserScopedRepository}: no inherited {@code findById}, no {@code findAll}, and
 * the one query below names its household (§9 risk 2).
 */
public interface SavingsGoalRepository extends UserScopedRepository<SavingsGoal, UUID> {

    /** The household's goals, newest first. Bounded by construction — a household has a few. */
    List<SavingsGoal> findAllByHouseholdIdOrderByCreatedAtDesc(UUID householdId);
}
