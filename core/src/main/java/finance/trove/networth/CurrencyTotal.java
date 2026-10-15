package finance.trove.networth;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;

/**
 * One currency's aggregate total — the projection
 * {@link AssetRepository#sumValueByHouseholdIdGroupByCurrency} and
 * {@link LiabilityRepository#sumBalanceByHouseholdIdGroupByCurrency} return, one row per currency
 * actually present ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.5, §6 "Net worth", F2).
 *
 * <p>There is deliberately no method anywhere in this package that collapses two rows of this
 * type into one blended total: {@link finance.trove.common.domain.Money} already refuses to add
 * or compare amounts of different currencies at runtime, and a household holding both CAD and USD
 * positions is meant to produce two of these, never a converted sum.
 */
public record CurrencyTotal(CurrencyCode currency, BigDecimal total) {
}
