package finance.trove.ingestion.dedup;

/**
 * What deduplication did with one incoming row.
 *
 * <p>The three outcomes are what a {@code sync_log} counts and what
 * {@code TransactionsIngestedEvent} splits its two id lists on: a new row needs categorising, an
 * updated one only needs its aggregates recomputed, and a skipped one needs nothing at all.
 */
public enum DeduplicationResult {

    /** No existing row described this transaction; one was inserted. */
    NEW,

    /** An existing row was this transaction, and it changed — a pending row that has posted. */
    UPDATED,

    /** An existing row was this transaction and needed no change. */
    SKIPPED
}
