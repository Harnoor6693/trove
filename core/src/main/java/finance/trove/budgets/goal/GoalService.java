package finance.trove.budgets.goal;

import finance.trove.budgets.goal.dto.GoalRequest;
import finance.trove.budgets.goal.dto.GoalUpdateRequest;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.time.UserClock;
import finance.trove.users.UserRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over a household's {@link SavingsGoal}s ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6).
 *
 * <p>Ownership is 404-not-403 by construction: every read and write goes through
 * {@link SavingsGoalRepository}'s household-scoped finders, inherited from
 * {@link finance.trove.common.repository.UserScopedRepository}, so an id belonging to another
 * household simply is not found — never a 403 that would confirm the row exists (§9 risk 2).
 *
 * <p>The source scoped by {@code user_id} and filtered in Java with
 * {@code findById(id).filter(g -> g.getUserId().equals(userId))}, which is the shape that only
 * works while nobody forgets the filter.
 */
@Service
public class GoalService {

    private final SavingsGoalRepository goals;
    private final UserRepository users;
    private final UserClock clock;

    public GoalService(SavingsGoalRepository goals, UserRepository users, UserClock clock) {
        this.goals = goals;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SavingsGoal> list(UUID householdId) {
        return goals.findAllByHouseholdIdOrderByCreatedAtDesc(householdId);
    }

    /**
     * Creates a goal. {@code currency} defaults to the creating member's own
     * {@code users.currency} when the request omits it — there is no literal default anywhere
     * (finding F1, §9 risk 5).
     *
     * <p>{@code currentAmount} starts at zero and {@code achieved} at false whatever the caller
     * sent: those are the entity's construction invariants, not something this method has to
     * remember to impose.
     */
    @Transactional
    public SavingsGoal create(UUID householdId, UUID userId, GoalRequest request) {
        CurrencyCode currency = request.currency() != null ? request.currency()
            : creatingMemberCurrency(householdId, userId);
        return goals.save(new SavingsGoal(householdId, userId, request.name().trim(),
            currency.normalise(request.targetAmount()), request.deadline(), currency,
            clock.now()));
    }

    /**
     * Applies a full replace and re-derives {@code achieved} from the two amounts.
     *
     * <p>Both amounts are re-rounded to the goal's own currency scale on the way in, so a client
     * that sent four decimal places on a two-decimal currency stores what it will be shown rather
     * than a figure that changes shape on the next read (§9 risk 6).
     */
    @Transactional
    public SavingsGoal update(UUID householdId, UUID goalId, GoalUpdateRequest request) {
        SavingsGoal goal = goals.findByIdAndHouseholdId(goalId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Savings goal", goalId));
        CurrencyCode currency = goal.getCurrency();
        goal.recordProgress(request.name().trim(), currency.normalise(request.targetAmount()),
            currency.normalise(request.currentAmount()), request.deadline());
        return goals.save(goal);
    }

    @Transactional
    public void delete(UUID householdId, UUID goalId) {
        if (goals.deleteByIdAndHouseholdId(goalId, householdId) == 0) {
            throw ResourceNotFoundException.of("Savings goal", goalId);
        }
    }

    private CurrencyCode creatingMemberCurrency(UUID householdId, UUID userId) {
        return users.findByIdAndHouseholdId(userId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId))
            .getCurrency();
    }
}
