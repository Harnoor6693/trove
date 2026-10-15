package finance.trove.networth;

import finance.trove.common.repository.UserScopedRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link Asset} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5, §6 "Net worth").
 *
 * <p>Extends {@link UserScopedRepository}, so it inherits no {@code findById}/{@code findAll}:
 * every query takes a {@code householdId}, which is what makes a cross-household read a compile
 * error rather than a review question (§9 risk 2).
 */
public interface AssetRepository extends UserScopedRepository<Asset, UUID> {

    /** The household's assets. Bounded by construction — a household has few. */
    List<Asset> findAllByHouseholdIdOrderByCreatedAtDesc(UUID householdId);

    /**
     * One row PER CURRENCY actually held, never one blended sum (F2 — this is the whole point of
     * this step). {@code GROUP BY} means a currency with no assets produces no row at all, not a
     * zero row: {@link NetWorthSnapshotService#takeSnapshot} writes a snapshot only for a currency
     * that is actually present.
     *
     * @return one {@link CurrencyTotal} per currency this household holds at least one asset in;
     *         empty if the household has none
     */
    @Query("select new finance.trove.networth.CurrencyTotal(a.currency, sum(a.value)) "
        + "from Asset a where a.householdId = :householdId group by a.currency")
    List<CurrencyTotal> sumValueByHouseholdIdGroupByCurrency(@Param("householdId") UUID householdId);
}
