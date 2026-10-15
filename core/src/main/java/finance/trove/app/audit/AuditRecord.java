package finance.trove.app.audit;

import java.util.UUID;

/**
 * What {@code AuditLogFilter} captured about one request, on its way to {@link AuditLogWriter}.
 *
 * <p>Separate from {@link AuditLogEntry} because they travel in opposite directions and must be
 * free to differ: this one has a {@code householdId} (the scoping column of {@code GET /v1/audit})
 * that the response deliberately does not carry, and it has no {@code id} because the database
 * assigns it.
 *
 * <p>It holds nothing that could not be written to a log file: no body, no headers, no query
 * string, no token. That is a property of what the filter puts in it, and the reason the filter
 * reads {@code RequestPath.describe} rather than {@code getRequestURI}.
 */
public record AuditRecord(String method, String path, int status, UUID userId, UUID householdId,
                          String clientIp, UUID requestId, long durationMs) {
}
