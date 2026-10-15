package finance.trove.budgets.period.dto;

import finance.trove.budgets.period.BudgetType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * {@code PUT /v1/budgets/{id}} body — a full replace of the two mutable fields.
 *
 * <p>Both are required, unlike the pre-collapse partial patch, and for the same reason step 9's
 * {@code AssetUpdateRequest} is a full replace: a PUT that silently keeps whatever the caller
 * omitted cannot be distinguished, on the wire, from one that meant to clear it.
 *
 * <p>{@code month} and {@code currency} are absent because neither is writable. The month is the
 * period's identity inside the household, and re-denominating a period would silently reinterpret
 * every envelope figure already recorded under it.
 */
public record BudgetPeriodUpdateRequest(@NotNull @DecimalMin(value = "0.00", inclusive = true)
                                        BigDecimal totalIncome,
                                        @NotNull BudgetType budgetType) {
}
