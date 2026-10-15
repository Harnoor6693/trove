package finance.trove.notifications.inapp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code GET /v1/notifications/unread-count} (§4.9).
 *
 * <p>An object rather than a bare number, which is what the source returned. A named field is what
 * a generated client type can carry; {@code data: 3} gives every client a {@code number} with no
 * name and no room to grow.
 *
 * <p>This route is the whole of the mobile notification story: there is no push channel (owner
 * decision, 2026-09-03), so the mobile client polls this on foreground and background fetch and
 * raises a client-local notification from the result.
 *
 * @param unread notifications that are neither read nor dismissed
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record UnreadCountResponse(long unread) {
}
