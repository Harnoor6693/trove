package finance.trove.dataexport;

/**
 * Where one {@link DataExportRun} is (collapse step 18; gap row 16).
 *
 * <p>The transitions are {@code PENDING -> GENERATING -> COMPLETE} and, from either of the first
 * two, {@code -> FAILED}. Deliberately the same four names, in the same order, as
 * {@code TaxExportStatus}: the two features are the same async 202 / poll / download shape, and a
 * second vocabulary for the same states would only be a second thing for a client to learn.
 *
 * <p>Every transition is written by {@link DataExportStatusUpdater} in its own
 * {@code REQUIRES_NEW} transaction, which is what makes a failure inside the generation record
 * {@code FAILED} instead of rolling its own status write back with it.
 */
public enum DataExportStatus {

    /** Recorded synchronously by the POST, before the work is dispatched. */
    PENDING,

    /** The background worker has picked the run up. */
    GENERATING,

    /** The archive was written; {@code file_key}, {@code file_size_bytes} and expiry are set. */
    COMPLETE,

    /** Generation failed, or the executor refused the work; {@code error_message} says why. */
    FAILED;

    /**
     * Whether this status could have an archive at all.
     *
     * <p>Only half the question the download asks — {@link DataExportRun#isDownloadable} adds the
     * other half, because a {@code COMPLETE} run whose 24-hour link has lapsed has no file either.
     */
    public boolean canHaveFile() {
        return this == COMPLETE;
    }
}
