package finance.trove.networth.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.networth.Liability;
import finance.trove.networth.LiabilityType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A liability as {@code GET/POST/PUT /v1/networth/liabilities*} returns it.
 *
 * @param createdBy the member whose action produced the row, for audit; never the scope
 * @param interestRate a percentage, not money — {@code "4.2500"} means 4.25%, never an amount in
 *                     {@code currency}
 */
public record LiabilityResponse(UUID id, UUID householdId, UUID createdBy, String name,
                                LiabilityType liabilityType, BigDecimal balance,
                                BigDecimal interestRate, CurrencyCode currency, UUID accountId,
                                Instant createdAt, Instant updatedAt) {

    public static LiabilityResponse of(Liability liability) {
        return new LiabilityResponse(liability.getId(), liability.getHouseholdId(),
            liability.getUserId(), liability.getName(), liability.getLiabilityType(),
            liability.getBalance(), liability.getInterestRate(), liability.getCurrency(),
            liability.getAccountId(), liability.getCreatedAt(), liability.getUpdatedAt());
    }
}
