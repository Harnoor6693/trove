package finance.trove.networth.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.networth.NetWorthSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One {@link NetWorthSnapshot} row — one currency, one date. {@code POST /v1/networth/snapshot}
 * answers an array of these, one per currency present in the household (F2).
 *
 * @param createdBy who triggered this snapshot: the calling member for the button, the
 *                  household's own administrator for the daily job (there is no caller to
 *                  attribute a scheduled write to). For audit only, never a scope.
 */
public record NetWorthSnapshotResponse(UUID id, UUID householdId, UUID createdBy,
                                       LocalDate snapshotDate, BigDecimal totalAssets,
                                       BigDecimal totalLiabilities, BigDecimal netWorth,
                                       CurrencyCode currency, Instant createdAt) {

    /**
     * {@code netWorth} is computed here rather than read off {@link NetWorthSnapshot#getNetWorth()}
     * because that field is a database-GENERATED column: Hibernate does not re-fetch it after an
     * {@code INSERT}/{@code UPDATE} in the same persistence context, only on a later
     * {@code SELECT}, so it would be {@code null} on the very entity
     * {@link finance.trove.networth.NetWorthSnapshotService#takeSnapshot} just wrote. Computing it
     * here matches what Postgres would compute, without a round trip to prove it.
     */
    public static NetWorthSnapshotResponse of(NetWorthSnapshot snapshot) {
        BigDecimal netWorth = snapshot.getNetWorth() != null ? snapshot.getNetWorth()
            : snapshot.getTotalAssets().subtract(snapshot.getTotalLiabilities());
        return new NetWorthSnapshotResponse(snapshot.getId(), snapshot.getHouseholdId(),
            snapshot.getUserId(), snapshot.getSnapshotDate(), snapshot.getTotalAssets(),
            snapshot.getTotalLiabilities(), netWorth, snapshot.getCurrency(),
            snapshot.getCreatedAt());
    }
}
