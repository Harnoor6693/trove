package finance.trove.dataexport.dto;

import finance.trove.dataexport.DataExportRun;
import finance.trove.dataexport.DataExportStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One data-export run, as every route under {@code /v1/me/export} returns it.
 *
 * <p>{@code userId} is the SCOPE, not audit: a household mate cannot read or download this row.
 * That is the opposite of {@code createdBy} on every household-scoped DTO in the tree.
 *
 * <p>{@code fileKey} is deliberately NOT exposed, and neither is {@code householdId}. The first is
 * a server-side storage key that the download route resolves itself, and a client that had it
 * could only be tempted to build a path from it; the second is on the row solely so the sweep and
 * the purge can resolve a file with no request in hand, and it tells a client nothing it does not
 * already know about itself.
 *
 * @param fileSizeBytes null until the run is COMPLETE, null for ever on a FAILED one, and null
 *                      again once the expiry sweep has removed the archive
 * @param errorMessage  a truncated reason, never a stack trace and never a path
 * @param expiresAt     when the download link lapses; null until the run is COMPLETE
 */
public record DataExportRunResponse(UUID id, UUID userId, DataExportStatus status,
                                    Long fileSizeBytes, String errorMessage, Instant expiresAt,
                                    Instant createdAt, Instant updatedAt) {

    public static DataExportRunResponse of(DataExportRun run) {
        return new DataExportRunResponse(run.getId(), run.getUserId(), run.getStatus(),
            run.getFileSizeBytes(), run.getErrorMessage(), run.getExpiresAt(), run.getCreatedAt(),
            run.getUpdatedAt());
    }
}
