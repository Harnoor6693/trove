package finance.trove.networth.dto;

import finance.trove.networth.AssetType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code PUT /v1/networth/assets/{id}} body. A full replace of the mutable fields, not a partial
 * patch — {@code accountId} omitted and {@code accountId: null} are indistinguishable and both
 * mean "no linked account".
 *
 * <p>{@code currency} is deliberately absent: it is the denomination {@code value} was already
 * recorded in, and letting an edit form change it would silently reinterpret a figure already on
 * the books, with no conversion anywhere in this product to reinterpret it correctly.
 */
public record AssetUpdateRequest(@NotBlank @Size(max = 200) String name,
                                 @NotNull AssetType assetType,
                                 @NotNull @DecimalMin(value = "0.00", inclusive = true)
                                 @Digits(integer = 15, fraction = 4)
                                 BigDecimal value, UUID accountId) {
}
