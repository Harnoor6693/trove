package finance.trove.ingestion.importbatch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The ceilings every file import is held to ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.6;
 * {@code docs/recovery/07-READINESS-GAPS.md} P2-11, P2-12).
 *
 * <p>They are separate from, and smaller than, {@code spring.servlet.multipart.max-file-size}.
 * That one is the container's: it refuses an oversized body before any handler runs, which is what
 * keeps a 200 MB upload from being buffered at all. These are the import's own, and the file cap
 * is checked against the part's <strong>declared</strong> size before its bytes are read, so a
 * file between the two ceilings is refused without being stored either.
 *
 * <p>{@link #maxFieldChars()} bounds one cell rather than the file, because a single-row CSV whose
 * description column is ten megabytes long is under every other limit here and would still become
 * a ten-megabyte {@code TEXT} value in the ledger.
 *
 * <p>{@link #parseBudget()} bounds the whole parse, and {@code BoundedRegex} bounds each individual
 * header-guess match inside it: a crafted header must not be able to pin a request thread
 * (plugins.md §8.2's ReDoS note).
 *
 * @param maxFileBytes        largest upload this endpoint accepts, in bytes
 * @param maxRows             most data rows one file may contain
 * @param maxFieldChars       longest single cell, in characters
 * @param parseBudget         wall-clock ceiling on parsing one file
 * <p>{@link #householdUploadQuotaBytes()} and {@link #previewRetention()} are the two that bound
 * the VOLUME rather than one file (confirmed review finding 3). An upload is stored before it is
 * committed, and a batch that is abandoned in {@code PREVIEW} — the member changed their mind, or
 * the commit was refused — would otherwise keep its bytes on the shared data volume for ever, with
 * nothing anywhere sweeping {@code StorageArea.UPLOADS}. The quota refuses the upload that would
 * take a household past its allowance; the retention window is what {@code ImportRetentionJob}
 * deletes an abandoned batch and its file after.
 *
 * @param previewRows         how many parsed rows the preview shows
 * @param previewSampleValues how many date cells a format candidate quotes
 * @param householdUploadQuotaBytes most bytes of un-swept uploads one household may hold at once
 * @param previewRetention    how long an uncommitted {@code PREVIEW} batch and its file are kept
 */
@ConfigurationProperties(prefix = "trove.import")
public record ImportLimits(long maxFileBytes, int maxRows, int maxFieldChars, Duration parseBudget,
                           int previewRows, int previewSampleValues,
                           long householdUploadQuotaBytes, Duration previewRetention) {

    /** 10 MB (§1.6). */
    public static final long DEFAULT_MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** 50,000 rows (§1.6). */
    public static final int DEFAULT_MAX_ROWS = 50_000;

    /** 1,000 characters per field (§1.6). */
    public static final int DEFAULT_MAX_FIELD_CHARS = 1_000;

    /** 60 seconds to parse one file (§1.6). */
    public static final Duration DEFAULT_PARSE_BUDGET = Duration.ofSeconds(60);

    private static final int DEFAULT_PREVIEW_ROWS = 10;

    private static final int DEFAULT_PREVIEW_SAMPLE_VALUES = 3;

    /**
     * 256 MB of stored uploads per household — twenty-five files at the 10 MB file cap.
     *
     * <p>Generous for the feature and finite for the volume, which is the point: without it any
     * authenticated member can grow the shared {@code trove_data} volume without bound, ten
     * megabytes at a time, and it is the same volume the readiness probe watches for free space.
     */
    public static final long DEFAULT_HOUSEHOLD_UPLOAD_QUOTA_BYTES = 256L * 1024 * 1024;

    /**
     * Seven days.
     *
     * <p>Long enough that a member who uploads a statement on Friday and confirms the mapping the
     * following Thursday still finds their preview; short enough that an abandoned batch is not a
     * permanent tenant of the volume.
     */
    public static final Duration DEFAULT_PREVIEW_RETENTION = Duration.ofDays(7);

    public ImportLimits {
        if (maxFileBytes <= 0) {
            maxFileBytes = DEFAULT_MAX_FILE_BYTES;
        }
        if (maxRows <= 0) {
            maxRows = DEFAULT_MAX_ROWS;
        }
        if (maxFieldChars <= 0) {
            maxFieldChars = DEFAULT_MAX_FIELD_CHARS;
        }
        if (parseBudget == null || parseBudget.isZero() || parseBudget.isNegative()) {
            parseBudget = DEFAULT_PARSE_BUDGET;
        }
        if (previewRows <= 0) {
            previewRows = DEFAULT_PREVIEW_ROWS;
        }
        if (previewSampleValues <= 0) {
            previewSampleValues = DEFAULT_PREVIEW_SAMPLE_VALUES;
        }
        if (householdUploadQuotaBytes <= 0) {
            householdUploadQuotaBytes = DEFAULT_HOUSEHOLD_UPLOAD_QUOTA_BYTES;
        }
        if (previewRetention == null || previewRetention.isZero()
            || previewRetention.isNegative()) {
            previewRetention = DEFAULT_PREVIEW_RETENTION;
        }
    }

    /** The defaults, for a unit test that needs the production numbers without a context. */
    public static ImportLimits defaults() {
        return new ImportLimits(DEFAULT_MAX_FILE_BYTES, DEFAULT_MAX_ROWS, DEFAULT_MAX_FIELD_CHARS,
            DEFAULT_PARSE_BUDGET, DEFAULT_PREVIEW_ROWS, DEFAULT_PREVIEW_SAMPLE_VALUES,
            DEFAULT_HOUSEHOLD_UPLOAD_QUOTA_BYTES, DEFAULT_PREVIEW_RETENTION);
    }
}
