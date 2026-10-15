package finance.trove.ingestion;

import finance.trove.common.plugin.SourceKind;

/**
 * The shape of {@code import_batch.mapping_json} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3
 * V20-row; {@code docs/recovery/07-READINESS-GAPS.md} P2-12).
 *
 * <p><strong>Why these four fields live in a JSONB column rather than in columns of their own.</strong>
 * V22 gives {@code import_batch} no column for the stored upload, and this step adds no migration.
 * The storage key is not a caller's choice either: {@code LocalDiskStorage.put(...)} <em>mints</em>
 * the key ({@code uploads/<householdId>/<uuid>.<ext>}) so that no caller-controlled component ever
 * appears in a path. So the key, the format the sniffer detected, the name the browser sent, and
 * the column mapping the member confirmed are all recorded here, in one closed record, and
 * serialised into the one column V22 provides for exactly this.
 *
 * <p>A record and not a {@code Map<String,Object>}: the commit reads this back long after the
 * preview wrote it, and a free map cannot tell a renamed key from a missing one.
 *
 * @param storageKey       the key {@code LocalDiskStorage} minted for the uploaded bytes. Only
 *                         ever resolved through {@code LocalDiskStorage.pathOf}, which re-checks
 *                         it on the way back in
 * @param originalFilename the name the browser sent, for display only. Never part of a path
 * @param format           what the sniffer decided the bytes are — {@code CSV} or {@code OFX},
 *                         the two values {@code import_batch.source_kind}'s CHECK allows
 * @param csv              the column mapping, or {@code null} for OFX, which needs none
 */
public record ImportMapping(String storageKey, String originalFilename, SourceKind format,
                            CsvColumnMapping csv) {

    /** This mapping with a different column mapping — what a commit's adjustment produces. */
    public ImportMapping withCsv(CsvColumnMapping replacement) {
        return new ImportMapping(storageKey, originalFilename, format, replacement);
    }
}
