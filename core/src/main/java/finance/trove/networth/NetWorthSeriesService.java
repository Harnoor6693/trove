package finance.trove.networth;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.time.UserClock;
import finance.trove.networth.dto.NetWorthPoint;
import finance.trove.networth.dto.NetWorthSeries;
import finance.trove.networth.dto.NetWorthSeriesResponse;
import finance.trove.users.UserRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles {@code GET /v1/networth/series} — the household's net-worth history, one series per
 * currency ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 "Net worth", F2).
 *
 * <p><strong>This service only reads.</strong> The pre-collapse {@code NetWorthService#getSeries}
 * took a snapshot synchronously as a side effect whenever the requested range had none — a read
 * that writes is not idempotent, breaks under a read-only transaction, and makes the route unsafe
 * to retry (the inventory's own note on this route). An empty range here answers an empty
 * {@code series} list; {@code POST /v1/networth/snapshot} and {@link NetWorthDailySnapshotJob} are
 * the only writers.
 *
 * <p>{@code range} bounds are computed from the CALLING member's own "today" ({@link UserClock}):
 * unlike the snapshot date (which is a write, and needs one rule shared by every trigger — see
 * {@link NetWorthSnapshotService}'s javadoc), this is a per-request read filter, and each caller
 * seeing the range relative to their own local today is the same rule the dashboard already
 * applies.
 */
@Service
public class NetWorthSeriesService {

    private final NetWorthSnapshotRepository snapshots;
    private final UserRepository users;
    private final UserClock clock;

    public NetWorthSeriesService(NetWorthSnapshotRepository snapshots, UserRepository users,
                                 UserClock clock) {
        this.snapshots = snapshots;
        this.users = users;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public NetWorthSeriesResponse getSeries(UUID householdId, UUID callerId, NetWorthRange range) {
        CurrencyCode primaryCurrency = users.findByIdAndHouseholdId(callerId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("User not found: " + callerId))
            .getCurrency();

        LocalDate today = clock.today(callerId);
        LocalDate from = switch (range) {
            case THREE_MONTHS -> today.minusDays(90);
            case SIX_MONTHS -> today.minusDays(180);
            case ONE_YEAR -> today.minusDays(365);
            case ALL -> LocalDate.of(2000, 1, 1);
        };

        List<NetWorthSnapshot> rows = snapshots
            .findAllByHouseholdIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
                householdId, from);

        Map<CurrencyCode, List<NetWorthPoint>> byCurrency = new EnumMap<>(CurrencyCode.class);
        for (NetWorthSnapshot row : rows) {
            byCurrency.computeIfAbsent(row.getCurrency(), key -> new ArrayList<>())
                .add(NetWorthPoint.of(row));
        }

        List<NetWorthSeries> series = new ArrayList<>();
        byCurrency.forEach((currency, points) -> series.add(new NetWorthSeries(currency, points)));

        return new NetWorthSeriesResponse(primaryCurrency, series);
    }
}
