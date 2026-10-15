package finance.trove.networth.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.networth.Asset;
import finance.trove.networth.AssetType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An asset as {@code GET/POST/PUT /v1/networth/assets*} returns it.
 *
 * <p>{@code value} reaches the wire as a decimal string — the global {@code BigDecimal}
 * serializer in {@code TroveJsonModule} sees to that for every monetary field (§5.4).
 *
 * @param createdBy the member whose action produced the row, for audit; never the scope
 */
public record AssetResponse(UUID id, UUID householdId, UUID createdBy, String name,
                            AssetType assetType, BigDecimal value, CurrencyCode currency,
                            UUID accountId, Instant createdAt, Instant updatedAt) {

    public static AssetResponse of(Asset asset) {
        return new AssetResponse(asset.getId(), asset.getHouseholdId(), asset.getUserId(),
            asset.getName(), asset.getAssetType(), asset.getValue(), asset.getCurrency(),
            asset.getAccountId(), asset.getCreatedAt(), asset.getUpdatedAt());
    }
}
