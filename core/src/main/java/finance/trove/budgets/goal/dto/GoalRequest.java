package finance.trove.budgets.goal.dto;

import finance.trove.common.domain.CurrencyCode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code POST /v1/goals} body ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6).
 *
 * <p>{@code currentAmount} and {@code achieved} are not accepted: a goal starts at zero and unmet
 * whatever the caller sends, which is the one behaviour of the source's {@code GoalService.create}
 * worth keeping verbatim — it is what stops a client creating a goal already marked complete.
 *
 * @param currency {@code null} means "the creating member's own {@code users.currency}"
 * @param deadline {@code null} for an open-ended goal
 */
public record GoalRequest(@NotBlank @Size(max = 200) String name,
                          @NotNull @DecimalMin(value = "0.00", inclusive = true)
                          @Digits(integer = 15, fraction = 4)
                          BigDecimal targetAmount,
                          CurrencyCode currency, LocalDate deadline) {
}
