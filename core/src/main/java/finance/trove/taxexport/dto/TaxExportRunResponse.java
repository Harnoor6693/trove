package finance.trove.taxexport.dto;

import finance.trove.common.domain.Jurisdiction;
import finance.trove.taxexport.TaxExportFormat;
import finance.trove.taxexport.TaxExportRun;
import finance.trove.taxexport.TaxExportStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One export run, as every route under {@code /v1/tax-export/runs} returns it.
 *
 * <p>{@code userId} is the SCOPE, not audit: a household mate cannot read, download or delete this
 * row. That is the opposite of {@code createdBy} on every household-scoped DTO in the tree.
 *
 * <p>{@code fileKey} is deliberately NOT exposed. It is a server-side storage key, the download
 * route resolves it itself, and a client that had it could only be tempted to build a path from
 * it.
 *
 * @param fileSizeBytes null until the run is COMPLETE, and null for ever on a FAILED one
 * @param errorMessage  a truncated reason, never a stack trace and never the address of anything
 */
public record TaxExportRunResponse(UUID id, UUID userId, int taxYear, Jurisdiction jurisdiction,
                                   TaxExportFormat format, TaxExportStatus status,
                                   Long fileSizeBytes, String errorMessage, Instant createdAt,
                                   Instant updatedAt) {

    public static TaxExportRunResponse of(TaxExportRun run) {
        return new TaxExportRunResponse(run.getId(), run.getUserId(), run.getTaxYear(),
            run.getJurisdiction(), run.getFormat(), run.getStatus(), run.getFileSizeBytes(),
            run.getErrorMessage(), run.getCreatedAt(), run.getUpdatedAt());
    }
}
