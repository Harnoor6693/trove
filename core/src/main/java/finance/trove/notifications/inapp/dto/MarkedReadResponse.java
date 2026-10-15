package finance.trove.notifications.inapp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code POST /v1/notifications/read-all} (§4.9).
 *
 * <p>The count is returned rather than an empty success because it is the number a client
 * decrements its badge by; without it the bell has to make a second request to find out what it
 * just did.
 *
 * @param marked how many rows this call changed; zero when nothing was unread
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MarkedReadResponse(int marked) {
}
