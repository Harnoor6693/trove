package finance.trove.subscriptions.dto;

import finance.trove.subscriptions.alert.AlertType;
import finance.trove.subscriptions.alert.SubscriptionAlert;
import java.time.Instant;
import java.util.UUID;

/**
 * A subscription alert as {@code GET /v1/subscriptions/alerts} and
 * {@code PATCH /v1/subscriptions/alerts/{id}/read} return it.
 *
 * @param createdBy the member the detection run was attributed to, for audit; never the scope
 * @param isRead    whether the alert has been acknowledged — named to match the column and the
 *                  wire field rather than being flattened to {@code read} by bean conventions
 */
public record SubscriptionAlertResponse(UUID id, UUID householdId, UUID createdBy,
                                        UUID subscriptionId, AlertType alertType,
                                        String alertMessage, boolean isRead, Instant createdAt) {

    public static SubscriptionAlertResponse of(SubscriptionAlert alert) {
        return new SubscriptionAlertResponse(alert.getId(), alert.getHouseholdId(),
            alert.getUserId(), alert.getSubscriptionId(), alert.getAlertType(),
            alert.getAlertMessage(), alert.isRead(), alert.getCreatedAt());
    }
}
