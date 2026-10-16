package finance.trove.taxexport;

/**
 * Where one {@link TaxExportRun} is ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9,
 * §3 V41-row (plan numbering; shipped as V35__tax_export_run.sql)).
 *
 * <p>The transitions are {@code PENDING -> GENERATING -> COMPLETE} and, from either of the first
 * two, {@code -> FAILED}. Every one of them is written by {@link TaxExportStatusUpdater} in its
 * own {@code REQUIRES_NEW} transaction, which is what makes a failure inside the generation
 * record {@code FAILED} instead of rolling its own status write back with it.
 */
public enum TaxExportStatus {

    /** Recorded synchronously by the POST, before the work is dispatched. */
    PENDING,

    /** The background worker has picked the run up. */
    GENERATING,

    /** There is a file to download; {@code file_key} and {@code file_size_bytes} are set. */
    COMPLETE,

    /** Generation failed, or the executor refused the work; {@code error_message} says why. */
    FAILED;

    /** Only a complete run has bytes to serve; everything else is a 409 on the download. */
    public boolean isDownloadable() {
        return this == COMPLETE;
    }
}
