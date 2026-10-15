package finance.trove.ingestion.importbatch;

/**
 * Where an import batch is, matching {@code import_batch.status}'s CHECK exactly (V22).
 *
 * <p>The transitions are one-way. {@link #PREVIEW} has written nothing to the ledger;
 * {@link #COMMITTED} has written its rows and stamped each of them with the batch's id;
 * {@link #UNDONE} has had the untouched ones taken back out. A committed batch is never previewed
 * again and an undone batch is never re-committed — committing twice is a mistake, and answering
 * 200 to the second attempt would hide it.
 */
public enum ImportBatchStatus {

    /** Uploaded, parsed and classified. Nothing has been written. */
    PREVIEW,

    /** Its rows are in the ledger, each carrying this batch's id. */
    COMMITTED,

    /** The rows nobody had touched have been removed. The stored upload has been deleted. */
    UNDONE
}
