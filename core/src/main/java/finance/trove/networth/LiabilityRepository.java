package finance.trove.networth;

import finance.trove.common.repository.UserScopedRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link Liability} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5,
 * §6 "Net worth").
 *
 * <p>See {@link AssetRepository}'s javadoc — the same tenancy and aggregation rules apply.
 */
public interface LiabilityRepository extends UserScopedRepository<Liability, UUID> {

    /** The household's liabilities. Bounded by construction — a household has few. */
    List<Liability> findAllByHouseholdIdOrderByCreatedAtDesc(UUID householdId);

    /**
     * One row PER CURRENCY actually owed, never one blended sum (F2). See
     * {@link AssetRepository#sumValueByHouseholdIdGroupByCurrency}'s javadoc — the same
     * reasoning applies symmetrically.
     *
     * @return one {@link CurrencyTotal} per currency this household owes at least one liability
     *         in; empty if the household has none
     */
    @Query("select new finance.trove.networth.CurrencyTotal(l.currency, sum(l.balance)) "
        + "from Liability l where l.householdId = :householdId group by l.currency")
    List<CurrencyTotal> sumBalanceByHouseholdIdGroupByCurrency(
        @Param("householdId") UUID householdId);
}
