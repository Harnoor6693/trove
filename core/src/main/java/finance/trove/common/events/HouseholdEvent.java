package finance.trove.common.events;

import java.util.UUID;

/**
 * Common shape of every domain event: the household whose ledger changed, and the member who
 * caused the change.
 *
 * <p>Listeners scope their work by {@link #householdId()}. {@link #userId()} is "who did this" —
 * it decides whose notification preferences apply and whose AI settings gate an LLM call, and it
 * is what an audit row records. It is never the scoping key for a ledger query.
 */
public interface HouseholdEvent {

    /** The household whose ledger this event concerns. Never {@code null}. */
    UUID householdId();

    /** The member who caused the event. Never {@code null}. */
    UUID userId();
}
