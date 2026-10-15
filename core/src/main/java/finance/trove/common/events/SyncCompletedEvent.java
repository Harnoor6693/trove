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
 */
public record SyncCompletedEvent(UUID householdId, UUID userId, UUID accountId, UUID syncLogId,
                                 Outcome outcome, int newCount, int updatedCount,
                                 String failureReason) implements HouseholdEvent {

    /** How a sync run ended. */
    public enum Outcome {
        /** Every row the source offered was processed. */
        SUCCESS,
        /** Some rows were written, some were rejected; {@code failureReason} says which. */
        PARTIAL_FAILURE,
        /** Nothing was written — the source was unreachable or refused the credentials. */
        FAILURE
    }

    public SyncCompletedEvent {
        Objects.requireNonNull(householdId, "householdId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(accountId, "accountId cannot be null");
        Objects.requireNonNull(outcome, "outcome cannot be null");
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }
}
