package finance.trove.budgets.period.dto;

import finance.trove.budgets.period.BudgetType;
import finance.trove.common.domain.CurrencyCode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code POST /v1/budgets} body ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6).
 *
 * <p>It carries no id and no household — the household comes from the signed token, never from
 * the body (§9 risk 2, and ArchUnit bans a request DTO field named {@code userId}).
 *
 * @param month      any day inside the target calendar month; normalised to the first of it
 *                   before storing
 * @param currency   {@code null} means "the creating member's own {@code users.currency}". There
 *                   is no literal default anywhere — finding F1, §9 risk 5
 * @param budgetType {@code null} means {@link BudgetType#FLEXIBLE}
 */
public record BudgetPeriodRequest(@NotNull LocalDate month,
                                  @NotNull @DecimalMin(value = "0.00", inclusive = true)
                                  BigDecimal totalIncome,
                                  CurrencyCode currency, BudgetType budgetType) {
}
