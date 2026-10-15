package finance.trove.ingestion;

import finance.trove.common.events.SyncCompletedEvent;

/**
 * How one sync run ended ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V20, §2.2;
 * {@code docs/recovery/inventory/ingestion-service.md} D4).
 *
 * <p>The pre-collapse code wrote {@code "OK"} and {@code "ERROR"} into a
 * {@code VARCHAR(10)} column and also wrote {@code "PARTIAL_FAILURE"} into it — fifteen
 * characters into a ten-character column. That survived only because the whole sync was
 * non-transactional and the exception was swallowed at the end; the moment the batch became
 * transactional it would have rolled back every ingested row with it. V20 widens the column,
 * CHECKs it against exactly these four names, and the three call sites are renamed to match.
 *
 * <p><strong>{@link #FAILED} maps to {@link SyncCompletedEvent.Outcome#FAILURE}.</strong> The
 * column and the published event spell the same state differently, and neither is renamed to suit
 * the other: the event is a contract other collapse steps consume and the column is what an
 * operator reads in SQL. {@link #toOutcome()} is the one place the mapping lives, so it cannot be
 * done inconsistently in two of them.
 */
public enum SyncRunStatus {

    /** The run has claimed the account and has not finished. */
    RUNNING,

    /** Every row the source offered was accepted. */
    SUCCESS,

    /** The fetch succeeded; some rows were refused. {@code transactions_failed} says how many. */
    PARTIAL_FAILURE,

    /**
     * <strong>Nothing was written.</strong> Either the source could not be reached or refused us,
     * or it answered and the batch write then failed and rolled back as a whole.
     *
     * <p>{@code transactions_fetched} therefore says what the run <em>read</em>, not what it kept,
     * and can be non-zero here; every other counter is zero. {@code last_synced_at} is never
     * stamped for this status, so the window those rows were in is fetched again on the next run.
     */
    FAILED;

    /** The event vocabulary for this state. {@link #RUNNING} has none — the run is not over. */
    public SyncCompletedEvent.Outcome toOutcome() {
        return switch (this) {
            case SUCCESS -> SyncCompletedEvent.Outcome.SUCCESS;
            case PARTIAL_FAILURE -> SyncCompletedEvent.Outcome.PARTIAL_FAILURE;
            case FAILED -> SyncCompletedEvent.Outcome.FAILURE;
            case RUNNING -> throw new IllegalStateException(
                "A run that is still RUNNING has no outcome to publish");
        };
    }
}
