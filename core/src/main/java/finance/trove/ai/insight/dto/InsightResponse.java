package finance.trove.ai.insight.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One weekly note, as {@code GET /v1/insights} returns it.
 *
 * <p>{@code content} is decrypted by {@code InsightService} and nowhere else, and only for the
 * member who owns the row.
 *
 * @param weekStart   the Monday of the week this note describes, in the RECIPIENT's own timezone —
 *                    not the server's. Also the idempotence key: one note per member per week
 * @param delivered   whether the note has been handed to the member anywhere beyond this route.
 *                    Reading the route does not set it
 * @param deliveredAt when {@code delivered} became true, or null
 */
public record InsightResponse(UUID id, LocalDate weekStart, String content, boolean delivered,
                              Instant createdAt, Instant deliveredAt) {
}
