package finance.trove.common.events;

import java.util.Objects;
import java.util.UUID;

/**
 * A receipt finished its pipeline, whether or not extraction was available
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, D3).
 *
 * <p>{@link Outcome#REVIEW_REQUIRED} is the ordinary outcome on an install with no AI configured:
 * the image is stored and attached, the fields are the member's to fill in. It is not an error
 * and the notification says so.
 *
 * @param householdId           the ledger
 * @param userId                the member who uploaded the receipt
 * @param receiptId             the {@code receipt} row
 * @param outcome               how the pipeline ended
 * @param matchedTransactionId  the transaction the receipt was linked to, or {@code null}
 */
public record ReceiptProcessedEvent(UUID householdId, UUID userId, UUID receiptId, Outcome outcome,
                                    UUID matchedTransactionId) implements HouseholdEvent {

    /** How a receipt's pipeline ended. */
    public enum Outcome {
        /** Fields were extracted and a transaction was matched; nothing for the member to do. */
        COMPLETE,
        /** Stored and attached, but the fields or the match need a human. */
        REVIEW_REQUIRED,
        /** The pipeline could not process the file; the original is still stored. */
        FAILED
    }

    public ReceiptProcessedEvent {
        Objects.requireNonNull(householdId, "householdId cannot be null");
        Objects.requireNonNull(userId, "userId cannot be null");
        Objects.requireNonNull(receiptId, "receiptId cannot be null");
        Objects.requireNonNull(outcome, "outcome cannot be null");
    }

    public boolean isMatched() {
        return matchedTransactionId != null;
    }
}
