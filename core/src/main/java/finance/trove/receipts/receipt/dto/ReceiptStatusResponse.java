package finance.trove.receipts.receipt.dto;

import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptStatus;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * The whole body of {@code GET /v1/receipts/{id}/status} (§4.10).
 *
 * <p>Four fields so that watching a receipt through its pipeline costs a fraction of fetching the
 * row each time. {@code COMPLETE} and {@code FAILED} are where a client stops polling;
 * {@code COMPLETE} with {@code reviewRequired} is the ordinary outcome on an install with no
 * vision model, not a failure.
 */
public record ReceiptStatusResponse(UUID id, ReceiptStatus status, boolean reviewRequired,
                                    BigDecimal confidence) {

    public static ReceiptStatusResponse of(Receipt receipt) {
        return new ReceiptStatusResponse(receipt.getId(), receipt.getStatus(),
            receipt.isReviewRequired(), receipt.getConfidence());
    }
}
