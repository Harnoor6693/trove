package finance.trove.ingestion.importbatch.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import finance.trove.common.plugin.SourceKind;
import finance.trove.ingestion.CsvColumnMapping;
import finance.trove.ingestion.DateFormatCandidate;
import finance.trove.ingestion.importbatch.ImportBatch;
import finance.trove.ingestion.importbatch.ImportBatchStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An uploaded batch and what committing it would do — the body of {@code POST /v1/imports} and of
 * {@code GET /v1/imports/{id}/preview} ({@code docs/recovery/07-READINESS-GAPS.md} P2-12).
 *
 * <p><strong>Nothing described here has been written to the ledger.</strong> The counts are a
 * projection against the household's ledger as it stands at the moment of the request, produced by
 * {@code DeduplicationService.classify}, which is the read-only half of the same rules the commit
 * then applies.
 *
 * <p>{@code rows} is a sample and not the file — enough for the member to see that the columns were
 * read the way they meant them, capped by {@code ImportLimits.previewRows} so that a
 * fifty-thousand-row statement does not become a fifty-thousand-row response.
 *
 * <p>{@code mapping} is annotated {@code NON_NULL} rather than declared nullable: it is absent for
 * an OFX batch, which has no columns to map. The contract says the same thing, and it says it that
 * way because a {@code $ref} with a {@code nullable} sibling is invisible to the request validator.
 *
 * @param originalFilename the name the browser sent, kept for display only — never part of a
 *                         storage path, because {@code LocalDiskStorage} mints its own keys
 */
public record ImportPreviewResponse(UUID id, UUID householdId, UUID accountId,
                                    SourceKind sourceKind, ImportBatchStatus status,
                                    String originalFilename, int rowCount, int newCount,
                                    int duplicateCount, int fuzzyDuplicateCount, int invalidCount,
                                    @JsonInclude(JsonInclude.Include.NON_NULL)
                                    CsvColumnMapping mapping,
                                    List<DateFormatCandidate> dateFormatCandidates,
                                    List<ImportPreviewRow> rows, List<String> warnings,
                                    Instant createdAt) {

    public ImportPreviewResponse {
        dateFormatCandidates = List.copyOf(dateFormatCandidates);
        rows = List.copyOf(rows);
        warnings = List.copyOf(warnings);
    }

    /**
     * The stored view of a batch that is no longer in {@code PREVIEW}.
     *
     * <p>A committed or undone batch is not re-read from disk: the file is deleted once the batch
     * can no longer be undone, and re-classifying a committed batch would report every one of its
     * own rows as a duplicate of itself. The counts the commit recorded are the answer.
     */
    public static ImportPreviewResponse ofStoredCounts(ImportBatch batch, String warning) {
        return new ImportPreviewResponse(batch.getId(), batch.getHouseholdId(),
            batch.getAccountId(), batch.getSourceKind(), batch.getStatus(),
            batch.getMappingJson() == null ? null : batch.getMappingJson().originalFilename(),
            batch.getRowCount(), batch.getNewCount(), batch.getDupCount(), 0, 0,
            batch.getMappingJson() == null ? null : batch.getMappingJson().csv(), List.of(),
            List.of(), List.of(warning), batch.getCreatedAt());
    }
}
