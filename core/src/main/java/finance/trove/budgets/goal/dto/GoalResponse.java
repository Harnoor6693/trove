package finance.trove.budgets.goal.dto;

import finance.trove.budgets.goal.SavingsGoal;
import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A savings goal as {@code GET/POST/PUT /v1/goals*} returns it.
 *
 * @param createdBy the member who started the goal, for audit; never the scope
 * @param achieved  derived from {@code currentAmount >= targetAmount}, never client-supplied
 */
public record GoalResponse(UUID id, UUID householdId, UUID createdBy, String name,
                           BigDecimal targetAmount, BigDecimal currentAmount, LocalDate deadline,
                           CurrencyCode currency, boolean achieved, Instant createdAt,
                           Instant updatedAt) {

    public static GoalResponse of(SavingsGoal goal) {
        return new GoalResponse(goal.getId(), goal.getHouseholdId(), goal.getUserId(),
            goal.getName(), goal.getTargetAmount(), goal.getCurrentAmount(), goal.getDeadline(),
            goal.getCurrency(), goal.isAchieved(), goal.getCreatedAt(), goal.getUpdatedAt());
    }
}
