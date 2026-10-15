package finance.trove.notifications.inapp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.inapp.InAppNotification;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the caller's inbox, as {@code GET /v1/notifications} and
 * {@code PATCH /v1/notifications/{id}/read} return it (§4.9).
 *
 * <p>{@code @JsonInclude(ALWAYS)} so the three nullable fields are on the wire as {@code null}
 * rather than absent, which is what lets the generated client types mark them {@code | null}
 * instead of optional. The spec's response schema is closed and its {@code required} list matches
 * this record exactly.
 *
 * <p>{@code body} is plain text, never HTML. It is rendered by the web bell and the mobile inbox,
 * and a merchant name — which is data this server received from a bank feed or a CSV somebody
 * uploaded — contributes to it.
 *
 * @param actionUrl a RELATIVE in-app path, or null. Never absolute: a notification is not a way to
 *                  send a member off this install
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record NotificationResponse(
    UUID id,
    NotificationType type,
    String title,
    String body,
    String actionUrl,
    boolean read,
    boolean dismissed,
    Instant createdAt,
    Instant readAt,
    Instant dismissedAt) {

    public static NotificationResponse of(InAppNotification notification) {
        return new NotificationResponse(
            notification.getId(),
            notification.getType(),
            notification.getTitle(),
            notification.getBody(),
            notification.getActionUrl(),
            notification.isRead(),
            notification.isDismissed(),
            notification.getCreatedAt(),
            notification.getReadAt(),
            notification.getDismissedAt());
    }
}
