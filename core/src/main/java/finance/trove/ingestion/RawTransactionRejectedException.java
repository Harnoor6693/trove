package finance.trove.ingestion;

/**
 * One incoming row could not be accepted — a missing date, a missing amount, a currency this
 * install does not support, a direction the source did not state.
 *
 * <p><strong>It is thrown before anything is written</strong>, and that timing is the whole
 * design. A per-row failure inside the batch transaction would mark it rollback-only, and the
 * {@code PARTIAL_FAILURE} log that ought to record the failure would then throw
 * {@code UnexpectedRollbackException} — so a partial failure could never be recorded, which is
 * exactly the defect {@code docs/recovery/06-COLLAPSE-PLAN.md} §6 warns about. Validating first
 * means a refused row costs a counter increment and leaves the persistence context untouched.
 *
 * <p>Its message reaches the {@code sync_log}'s {@code error_message}, so it says what was wrong
 * with the row without quoting the row: a transaction description is a household's spending
 * history.
 */
public class RawTransactionRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RawTransactionRejectedException(String message) {
        super(message);
    }
}
