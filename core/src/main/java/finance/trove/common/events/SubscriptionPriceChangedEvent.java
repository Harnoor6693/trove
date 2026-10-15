package finance.trove.common.events;

import finance.trove.common.domain.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * A detected recurring charge changed price
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, D2 → D3).
 *
 * <p>Published by {@code SubscriptionDetectionService} on the upsert that notices a new amount,
 * and consumed by the alert writer and the notification producer.
 *
 * @param householdId    the ledger
 * @param userId         the member the alert is attributed to
 * @param subscriptionId the {@code detected_subscription} row
 * @param merchantName   the normalised merchant, as shown to the member
 * @param previousAmount the amount charged before
 * @param newAmount      the amount charged now; same currency as {@code previousAmount}
 */
public record SubscriptionPriceChangedEvent(UUID householdId, UUID userId, UUID subscriptionId,
                                            String merchantName, Money previousAmount,
                                            Money newAmount) implements HouseholdEvent {

    public SubscriptionPriceChangedEvent {
        Objects.requireNonNull(householdId, "householdId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(subscriptionId, "subscriptionId cannot be null");
        Objects.requireNonNull(merchantName, "merchantName cannot be null");
        Objects.requireNonNull(previousAmount, "previousAmount cannot be null");
        Objects.requireNonNull(newAmount, "newAmount cannot be null");
        if (previousAmount.currency() != newAmount.currency()) {
            throw new IllegalArgumentException(
                "a price change cannot change currency: " + previousAmount.currency() + " to "
                    + newAmount.currency());
        }
    }

    /** The signed difference; negative when the subscription got cheaper. */
    public Money delta() {
        return newAmount.subtract(previousAmount);
    }
}
