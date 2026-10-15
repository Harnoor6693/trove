package finance.trove.app.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /v1/me/security-events} — the self-service "recent activity" list
 * (gap P2-17).
 *
 * <p>Deliberately thinner than the stored row. {@code actor_id} and {@code metadata} are omitted:
 * the metadata bag carries operational detail (which family was revoked, how many failures
 * preceded a lock) that is useful to an operator reading the table and is only noise, or a hint,
 * to whoever is reading their own history in a settings screen.
 *
 * @param id        the row
 * @param type      the event, as the {@link SecurityEventType} constant
 * @param at        when it happened
 * @param ip        the address it came from, or null for an event with no request behind it
 * @param userAgent a truncated user agent, or null
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SecurityEventView(UUID id, String type, Instant at, String ip, String userAgent) {
}
