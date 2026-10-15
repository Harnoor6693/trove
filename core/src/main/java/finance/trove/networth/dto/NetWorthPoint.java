package finance.trove.networth.dto;

import finance.trove.networth.NetWorthSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;

/** One dated point on a single-currency net-worth series. See {@link NetWorthSnapshotResponse}. */
public record NetWorthPoint(LocalDate snapshotDate, BigDecimal totalAssets,
                            BigDecimal totalLiabilities, BigDecimal netWorth) {

    public static NetWorthPoint of(NetWorthSnapshot snapshot) {
        BigDecimal netWorth = snapshot.getNetWorth() != null ? snapshot.getNetWorth()
            : snapshot.getTotalAssets().subtract(snapshot.getTotalLiabilities());
        return new NetWorthPoint(snapshot.getSnapshotDate(), snapshot.getTotalAssets(),
            snapshot.getTotalLiabilities(), netWorth);
    }
}
