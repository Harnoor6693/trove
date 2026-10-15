package finance.trove.budgets.period.dto;

import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetType;
import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A budget period as {@code GET/POST/PUT /v1/budgets*} returns it.
 *
 * <p>{@code totalIncome} reaches the wire as a decimal string — the global {@code BigDecimal}
 * serializer in {@code TroveJsonModule} sees to that for every monetary field (§5.4).
 *
 * @param createdBy    the member who opened the period, for audit; never the scope
 * @param month        always the first of the month, the stored normalised value
 * @param rolledOverAt when the rollover job carried this period forward, or {@code null}. It is
 *                     exposed because it is the honest answer to "why has this month stopped
 *                     changing" and because it is what makes the job's idempotence observable
 */
public record BudgetPeriodResponse(UUID id, UUID householdId, UUID createdBy, LocalDate month,
                                   BigDecimal totalIncome, CurrencyCode currency,
                                   BudgetType budgetType, Instant rolledOverAt, Instant createdAt,
                                   Instant updatedAt) {

    public static BudgetPeriodResponse of(BudgetPeriod period) {
        return new BudgetPeriodResponse(period.getId(), period.getHouseholdId(),
            period.getUserId(), period.getMonth(), period.getTotalIncome(), period.getCurrency(),
            period.getBudgetType(), period.getRolledOverAt(), period.getCreatedAt(),
            period.getUpdatedAt());
    }
}
