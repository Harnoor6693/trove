package finance.trove.networth.dto;

import finance.trove.networth.LiabilityType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code PUT /v1/networth/liabilities/{id}} body. A full replace of the mutable fields, not a
 * partial patch — {@code accountId} omitted and {@code accountId: null} are indistinguishable and
 * both mean "no linked account". {@code currency} is deliberately absent, for the same reason as
 * {@link AssetUpdateRequest}'s.
 */
public record LiabilityUpdateRequest(@NotBlank @Size(max = 200) String name,
                                     @NotNull LiabilityType liabilityType,
                                     @NotNull @DecimalMin(value = "0.00", inclusive = true)
                                     @Digits(integer = 15, fraction = 4)
                                     BigDecimal balance,
                                     @DecimalMin(value = "0.00", inclusive = true)
                                     @DecimalMax(value = "100") @Digits(integer = 3, fraction = 4)
                                     BigDecimal interestRate, UUID accountId) {
}
