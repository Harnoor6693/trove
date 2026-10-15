package finance.trove.networth.dto;

import finance.trove.common.domain.CurrencyCode;
import java.util.List;

/**
 * The household's net-worth history, grouped by currency (F2) — never one blended series
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 "Net worth"). {@code GET /v1/networth/series}
 * never writes; an empty {@code series} list means no snapshots exist in the requested range.
 *
 * @param primaryCurrency the caller's own {@code users.currency} — never a literal
 * @param series          one entry per currency with at least one snapshot in range, ordered by
 *                        currency code
 */
public record NetWorthSeriesResponse(CurrencyCode primaryCurrency, List<NetWorthSeries> series) {
}
