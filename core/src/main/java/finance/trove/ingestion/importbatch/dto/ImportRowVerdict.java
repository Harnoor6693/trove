package finance.trove.ingestion.importbatch.dto;

/**
 * What committing would do with one parsed row of an uploaded file.
 *
 * <p>{@link #DUPLICATE} and {@link #FUZZY_DUPLICATE} are kept apart because they mean different
 * things about confidence. An exact duplicate matched on the identifier the row carries — for a
 * CSV that is the deterministic hash of its own contents, so a re-imported file is recognised with
 * certainty. A fuzzy duplicate matched only on date and amount, which is a guess and is reported
 * as one; both file formats give every row an identifier, so a non-zero
 * {@code fuzzyDuplicateCount} means something has changed and is worth seeing.
 */
public enum ImportRowVerdict {

    /** Would be inserted. */
    NEW,

    /** Already in the ledger under the same identifier. */
    DUPLICATE,

    /** Matched by date and amount because the row carries no identifier of its own. */
    FUZZY_DUPLICATE,

    /** Cannot be accepted at all; the row's {@code reason} says why. */
    INVALID
}
