package finance.trove.networth;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.repository.UserScopedRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository over {@link NetWorthSnapshot} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5,
 * §6 "Net worth").
 */
public interface NetWorthSnapshotRepository extends UserScopedRepository<NetWorthSnapshot, UUID> {

    /**
     * The one row for this household, date and currency, if the daily job or an earlier
     * {@code POST /v1/networth/snapshot} on this same local date already wrote one.
     *
     * <p>{@link NetWorthSnapshotService#takeSnapshot} uses this to decide UPDATE vs INSERT:
     * re-running on the same local date must change the existing row's values rather than fail on
     * the {@code (household_id, snapshot_date, currency)} unique key or write a second set — the
     * daily job and the button are the same operation with different triggers.
     */
    Optional<NetWorthSnapshot> findByHouseholdIdAndSnapshotDateAndCurrency(
        UUID householdId, LocalDate snapshotDate, CurrencyCode currency);

    /**
     * Every snapshot of this household from {@code from} onward, across every currency, oldest
     * first. {@link NetWorthSeriesService} groups these by currency in memory to build one series
     * per currency (F2) — grouping in SQL here would gain nothing, since the whole range for a
     * household is a small, bounded set of rows.
     */
    List<NetWorthSnapshot> findAllByHouseholdIdAndSnapshotDate(UUID householdId,
                                                               LocalDate snapshotDate);

    List<NetWorthSnapshot> findAllByHouseholdIdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
        UUID householdId, LocalDate from);
}
