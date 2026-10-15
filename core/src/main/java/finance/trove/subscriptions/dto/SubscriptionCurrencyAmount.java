package finance.trove.subscriptions.dto;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;

/**
 * One currency's share of a single day's projected charges, inside {@link UpcomingCharge}.
 *
 * <p>A day on which a household is charged in two currencies produces two of these, never one
 * blended figure.
 */
public record SubscriptionCurrencyAmount(CurrencyCode currency, BigDecimal amount) {
}
