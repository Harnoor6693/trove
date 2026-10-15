package finance.trove.auth.session;

import java.time.Instant;
import java.util.UUID;

/**
 * One live session as {@code GET /v1/me/sessions} reports it.
 *
 * <p>The id is the <strong>family</strong> id, not a token id: a family is what a user thinks of
 * as "this browser" or "my phone", and it survives every rotation. Revoking by token id would end
 * a session the user had already rotated past, which from their side would look like nothing
 * happened.
 *
 * @param id         the family id — the value {@code DELETE /v1/me/sessions/{id}} takes
 * @param createdAt  when the family's first token was issued, i.e. when the user logged in
 * @param lastUsedAt when the family last rotated
 * @param expiresAt  the family's absolute deadline
 * @param ipAddress  the address the most recent rotation came from
 * @param userAgent  the client of the most recent rotation
 * @param current    whether this is the session making the request
 */
public record SessionSummary(
    UUID id,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt,
    String ipAddress,
    String userAgent,
    boolean current) {
}
