package finance.trove.common.events;

import finance.trove.common.domain.CurrencyCode;
import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * A transaction's category changed — by automatic categorisation after ingestion, or by a user's
 * {@code PATCH /v1/transactions/{id}} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, D4 → D1).
 *
 * <p>This is the seam that makes budgets move. {@code EnvelopeSpendListener} recomputes the
 * aggregate for <em>both</em> the old and the new category, which is why
 * {@link #previousCategoryId()} is carried rather than looked up: after the write it is gone.
 *
 * @param householdId        the ledger
 * @param userId             the member who caused the change
 * @param transactionId      the transaction
 * @param previousCategoryId the category before the change; {@code null} if it had none
 * @param categoryId         the category after the change; {@code null} if it was cleared
 * @param month              the month the transaction falls in, in the owner's timezone
 * @param currency           the transaction's currency — envelopes are per currency
 */
public record TransactionCategorisedEvent(UUID householdId, UUID userId, UUID transactionId,
                                          UUID previousCategoryId, UUID categoryId,
                                          YearMonth month, CurrencyCode currency)
    implements HouseholdEvent {

    public TransactionCategorisedEvent {
        Objects.requireNonNull(householdId, "householdId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(transactionId, "transactionId cannot be null");
        Objects.requireNonNull(month, "month cannot be null");
        Objects.requireNonNull(currency, "currency cannot be null");
    }
}
