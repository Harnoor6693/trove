package finance.trove.subscriptions.dto;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;

/**
 * One currency's subscription totals inside {@link SubscriptionSummaryResponse}.
 *
 * <p>There is deliberately no method anywhere in this package that collapses two of these into
 * one: {@code Money} refuses to add amounts of different currencies at runtime, no exchange rate
 * exists in this system, and a household holding both CAD and USD subscriptions is meant to
 * produce two of these rather than a converted sum (§9 risk 5) — the same shape
 * {@code finance.trove.networth.CurrencyTotal} takes for net worth.
 *
 * @param currency       the currency these subscriptions are billed in
 * @param monthlyRunRate every active subscription in this currency, normalised to a month
 * @param activeCount    how many there are
 * @param flaggedCount   how many carry an {@code AMBER} or {@code RED} signal
 */
public record SubscriptionCurrencyTotals(CurrencyCode currency, BigDecimal monthlyRunRate,
                                         int activeCount, int flaggedCount) {
}
