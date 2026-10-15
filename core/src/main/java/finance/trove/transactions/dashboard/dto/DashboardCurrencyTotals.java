package finance.trove.transactions.dashboard.dto;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;

/**
 * One currency's subtotals on the dashboard.
 *
 * <p>Currencies are reported separately and never converted. Trove holds no exchange rate and
 * asks for none (owner decision: CA and US, two currencies, no FX), so adding a USD amount to a
 * CAD one would be inventing a number — and a dashboard figure that is invented is worse than one
 * that is missing.
 */
public record DashboardCurrencyTotals(CurrencyCode currency, BigDecimal spentToday,
                                      int transactionsToday, BigDecimal spentThisMonth,
                                      BigDecimal incomeThisMonth, BigDecimal netThisMonth) {

    /** The all-zero subtotal, at the currency's own scale. */
    public static DashboardCurrencyTotals zero(CurrencyCode currency) {
        BigDecimal nothing = currency.normalise(BigDecimal.ZERO);
        return new DashboardCurrencyTotals(currency, nothing, 0, nothing, nothing, nothing);
    }
}
