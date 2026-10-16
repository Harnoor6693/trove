package finance.trove.budgets.goal.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * {@code PUT /v1/goals/{id}} body — a full replace of the writable fields.
 *
 * <p>{@code achieved} is derived from {@code currentAmount >= targetAmount} on every write and is
 * never sent; {@code currency} is not writable, being the denomination the amounts were recorded
 * in. {@code deadline} is nullable and this is a full replace, so omitting it or sending
 * {@code null} both clear it — the source's partial patch could only ever set a deadline, never
 * remove one.
 */
public record GoalUpdateRequest(@NotBlank @Size(max = 200) String name,
                                @NotNull @DecimalMin(value = "0.00", inclusive = true)
                                @Digits(integer = 15, fraction = 4)
                                BigDecimal targetAmount,
                                @NotNull @DecimalMin(value = "0.00", inclusive = true)
                                @Digits(integer = 15, fraction = 4)
                                BigDecimal currentAmount,
                                LocalDate deadline) {
}
