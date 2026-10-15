package finance.trove.transactions.dashboard.dto;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /v1/dashboard/today} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4; gap P2-15).
 *
 * @param date       the calendar date the figures describe, in {@code timezone} — the caller's
 *                   own, not the container's
 * @param timezone   the IANA zone the boundaries were computed in, so a client can say which day
 *                   it is showing rather than having to assume
 * @param currency   the caller's own {@code users.currency}; the five headline fields are that
 *                   currency's subtotal, and are zero when the household holds nothing in it
 * @param byCurrency one entry per currency present in the ledger, ordered by code
 */
public record DashboardTodayResponse(LocalDate date, String timezone, CurrencyCode currency,
                                     BigDecimal spentToday, int transactionsToday,
                                     BigDecimal spentThisMonth, BigDecimal incomeThisMonth,
                                     BigDecimal netThisMonth,
                                     List<DashboardCurrencyTotals> byCurrency) {

    public DashboardTodayResponse {
        byCurrency = List.copyOf(byCurrency);
    }

    /** Builds the response from the headline currency's subtotal plus the full breakdown. */
    public static DashboardTodayResponse of(LocalDate date, String timezone,
                                            DashboardCurrencyTotals headline,
                                            List<DashboardCurrencyTotals> byCurrency) {
        return new DashboardTodayResponse(date, timezone, headline.currency(),
            headline.spentToday(), headline.transactionsToday(), headline.spentThisMonth(),
            headline.incomeThisMonth(), headline.netThisMonth(), byCurrency);
    }
}
