package finance.trove.ingestion.importbatch.dto;

/**
 * Body of {@code POST /v1/imports/{id}/commit}. Optional: an absent body commits exactly what the
 * preview described.
 *
 * @param mapping a CSV column-mapping adjustment, or null. Sending one on an OFX batch is refused
 *                rather than ignored — an OFX file has no columns to map, and silently dropping
 *                the field would let a member believe an adjustment took effect
 */
public record ImportCommitRequest(CsvColumnMappingRequest mapping) {
}
