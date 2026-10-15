package finance.trove.common.events;

import java.util.Objects;
import java.util.UUID;

/**
 * An account sync finished, successfully or not
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, D3).
 *
 * <p>Failure is a first-class outcome here, not an exception that vanishes into a log: a
 * SimpleFIN connection whose credentials expired is exactly what a member needs to be told
 * about, and §9 risk 13's {@code NotificationServiceIT} asserts the failure path produces a
 * notification with the real reason.
 *
 * @param householdId   the ledger
 * @param userId        the member who owns the connection
 * @param accountId     the {@code connected_account} that was synced
 * @param syncLogId     the {@code sync_log} row this run wrote
 * @param outcome       how it ended
 * @param newCount      transactions inserted
 * @param updatedCount  transactions modified
 * @param failureReason human-readable cause; {@code null} when {@code outcome} is
 *                      {@link Outcome#SUCCESS}
 * @param failureAlert  how a failure of this run is announced (C-01r)
 */
public record SyncCompletedEvent(UUID householdId, UUID userId, UUID accountId, UUID syncLogId,
                                 Outcome outcome, int newCount, int updatedCount,
                                 String failureReason, FailureAlert failureAlert)
    implements HouseholdEvent {

    /** How a sync run ended. */
    public enum Outcome {
        /** Every row the source offered was processed. */
        SUCCESS,
        /** Some rows were written, some were rejected; {@code failureReason} says which. */
        PARTIAL_FAILURE,
        /** Nothing was written — the source was unreachable or refused the credentials. */
        FAILURE
    }

    /**
     * How a failed run is announced to its member (C-01r).
     *
     * <p>A member who pressed Sync is watching; a scheduled run is not, and a dead connection
     * would fail every day. So the scheduled job decides, before the run, whether the account was
     * healthy — and a failure is news only on the run that moves it from healthy to failing.
     */
    public enum FailureAlert {
        /** A manual run: at most one alert per account per recipient's local day. */
        DAILY,
        /** A scheduled run on a healthy account: a failure is announced, keyed by this run. */
        ONCE,
        /** A scheduled run on an account already failing: the member has been told. */
        NONE
    }

    public SyncCompletedEvent {
        Objects.requireNonNull(householdId, "householdId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(accountId, "accountId cannot be null");
        Objects.requireNonNull(outcome, "outcome cannot be null");
        Objects.requireNonNull(failureAlert, "failureAlert cannot be null");
    }

    /** A manual run, which is what every run was before C-01r. */
    public SyncCompletedEvent(UUID householdId, UUID userId, UUID accountId, UUID syncLogId,
                              Outcome outcome, int newCount, int updatedCount,
                              String failureReason) {
        this(householdId, userId, accountId, syncLogId, outcome, newCount, updatedCount,
            failureReason, FailureAlert.DAILY);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
