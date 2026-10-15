package finance.trove.subscriptions.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.subscriptions.detection.BillingCycle;
import finance.trove.subscriptions.subscription.DetectedSubscription;
import finance.trove.subscriptions.subscription.SubscriptionSignal;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A detected subscription as every {@code /v1/subscriptions} route returns it.
 *
 * <p>Amounts reach the wire as decimal strings — the global {@code BigDecimal} serializer in
 * {@code TroveJsonModule} sees to that for every monetary field (§5.4), so a client cannot parse a
 * ledger figure into an IEEE-754 double.
 *
 * <p>The transactions a detection was built from are deliberately NOT here. They live in
 * {@code subscription_transaction} (V29) as a real referential link; no route in plan §4.8 asks
 * for them, and a list of thirteen months of ids on every row of a list response would be paid for
 * by every client on every render.
 *
 * @param createdBy the member the detection run was attributed to, for audit; never the scope
 */
public record SubscriptionResponse(UUID id, UUID householdId, UUID createdBy, String merchantName,
                                   UUID categoryId, BigDecimal normalizedAmount,
                                   BigDecimal previousAmount, CurrencyCode currency,
                                   BillingCycle billingCycle, LocalDate nextChargeDate,
                                   SubscriptionSignal signal, boolean priceChanged,
                                   LocalDate firstSeenDate, LocalDate lastSeenDate,
                                   boolean cancelled, Instant createdAt, Instant updatedAt) {

    public static SubscriptionResponse of(DetectedSubscription subscription) {
        return new SubscriptionResponse(subscription.getId(), subscription.getHouseholdId(),
            subscription.getUserId(), subscription.getMerchantName(), subscription.getCategoryId(),
            subscription.getNormalizedAmount(), subscription.getPreviousAmount(),
            subscription.getCurrency(), subscription.getBillingCycle(),
            subscription.getNextChargeDate(), subscription.getSignal(),
            subscription.isPriceChanged(), subscription.getFirstSeenDate(),
            subscription.getLastSeenDate(), subscription.isCancelled(),
            subscription.getCreatedAt(), subscription.getUpdatedAt());
    }
}
