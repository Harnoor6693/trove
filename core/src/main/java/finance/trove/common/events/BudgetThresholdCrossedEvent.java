package finance.trove.common.events;

import finance.trove.common.domain.Money;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * An envelope's spend crossed the household's alert threshold
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, D1 → D3).
 *
 * <p>Published by {@code EnvelopeSpendService} after it recomputes an aggregate, and consumed by
 * the notification and webhook listeners. It is published on the crossing, not on every
 * recalculation, so a member does not get an alert per ingested transaction.
 *
 * @param householdId the ledger
 * @param userId      the member whose action moved the envelope
 * @param envelopeId  the envelope
 * @param categoryId  the category the envelope budgets
 * @param month       the budget period
 * @param spent       the recomputed spend
 * @param allocated   what was budgeted; same currency as {@code spent}
 */
public record BudgetThresholdCrossedEvent(UUID householdId, UUID userId, UUID envelopeId,
                                          UUID categoryId, YearMonth month, Money spent,
                                          Money allocated) implements HouseholdEvent {

    public BudgetThresholdCrossedEvent {
        Objects.requireNonNull(householdId, "householdId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(envelopeId, "envelopeId cannot be null");
        Objects.requireNonNull(month, "month cannot be null");
        Objects.requireNonNull(spent, "spent cannot be null");
        Objects.requireNonNull(allocated, "allocated cannot be null");
        if (spent.currency() != allocated.currency()) {
            throw new IllegalArgumentException(
                "spent and allocated must share a currency, got " + spent.currency() + " and "
                    + allocated.currency());
        }
    }

    /** Whether the envelope is over budget, as opposed to merely past the alert threshold. */
    public boolean isOverspent() {
        return spent.isGreaterThan(allocated);
    }
}
