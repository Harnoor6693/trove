package finance.trove.networth.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.networth.LiabilityType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code POST /v1/networth/liabilities} body ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6
 * "Net worth").
 *
 * @param interestRate {@code null} means zero — zero-interest debt
 * @param currency     {@code null} means "the creating member's own {@code users.currency}".
 *                     There is no literal default anywhere — §9 risk 5
 * @param accountId    an existing {@code connected_account} in the caller's OWN household, or
 *                     {@code null} for a plain manual entry; one naming another household's
 *                     account, or none at all, is a 400 {@code validation-error}
 */
public record LiabilityRequest(@NotBlank @Size(max = 200) String name,
                               @NotNull LiabilityType liabilityType,
                               @NotNull @DecimalMin(value = "0.00", inclusive = true)
                               BigDecimal balance,
                               @DecimalMin(value = "0.00", inclusive = true)
                               BigDecimal interestRate, CurrencyCode currency, UUID accountId) {
}
