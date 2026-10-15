package finance.trove.networth.dto;

import finance.trove.common.domain.CurrencyCode;
import java.util.List;

/**
 * One currency's net-worth history over the requested range
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 "Net worth", F2).
 *
 * @param points oldest first; empty when this currency has no snapshots in range
 */
public record NetWorthSeries(CurrencyCode currency, List<NetWorthPoint> points) {
}
