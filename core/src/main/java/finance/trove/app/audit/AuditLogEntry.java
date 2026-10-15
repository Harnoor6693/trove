package finance.trove.app.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of {@code GET /v1/audit} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.2, gap P2-17).
 *
 * <p>{@code ALWAYS} rather than {@code NON_NULL}: {@code userId} and {@code clientIp} are
 * genuinely nullable and a client rendering a table needs the column to exist rather than to
 * appear and disappear per row. The OpenAPI schema says the same thing — both are {@code required}
 * and {@code nullable: true}.
 *
 * <p>{@code householdId} is deliberately absent: the query is scoped to the caller's own
 * household, so every row would carry the same value and echoing it back only invites a client to
 * key off it.
 *
 * @param id         the audit row
 * @param method     the HTTP method; only mutating methods are recorded
 * @param path       the matched route template, context path included — never a raw URI
 * @param status     the status the client received
 * @param userId     the signed-in account, or null for an anonymous request
 * @param clientIp   the caller's address as the server resolved it, or null
 * @param requestId  the correlation id, matching the {@code X-Request-Id} of that response
 * @param durationMs how long the request took
 * @param at         when it completed
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditLogEntry(UUID id, String method, String path, int status, UUID userId,
                            String clientIp, UUID requestId, long durationMs, Instant at) {
}
