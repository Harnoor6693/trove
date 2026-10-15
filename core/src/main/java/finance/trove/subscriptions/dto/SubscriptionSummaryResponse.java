package finance.trove.subscriptions.dto;

import finance.trove.common.domain.CurrencyCode;
import java.util.List;

/**
 * The household's subscription aggregate, as {@code GET /v1/subscriptions/summary} returns it
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.8).
 *
 * <p><strong>There is no top-level money figure, deliberately.</strong> The pre-collapse response
 * had one {@code monthlyRunRate} and a {@code currency} field hardcoded to the literal
 * {@code "CAD"} ({@code SubscriptionService.java:59}, finding F1 / inventory SD-3), with a unit
 * test pinning that literal as correct behaviour. A household holding subscriptions in two
 * currencies has no single monthly total, so every amount here sits inside
 * {@link SubscriptionCurrencyTotals} or {@link UpcomingCharge#totals()}.
 *
 * @param currency     the CALLING member's own {@code users.currency} — never a literal, and
 *                     never derived from the rows. It says which currency this member reads
 *                     figures in and may not appear in {@code byCurrency} at all
 * @param activeCount  active subscriptions across every currency
 * @param flaggedCount how many of those carry an {@code AMBER} or {@code RED} signal
 * @param byCurrency   one entry per currency actually present, ordered by currency code
 * @param next14Days   projected charges over the summary's upcoming window, oldest first
 */
public record SubscriptionSummaryResponse(CurrencyCode currency, int activeCount, int flaggedCount,
                                          List<SubscriptionCurrencyTotals> byCurrency,
                                          List<UpcomingCharge> next14Days) {

    public SubscriptionSummaryResponse {
        byCurrency = List.copyOf(byCurrency);
        next14Days = List.copyOf(next14Days);
    }
}
