package finance.trove.receipts.receipt.dto;

import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * What {@code POST /v1/receipts/upload} answers (§4.10).
 *
 * <p><strong>Three fields, and the shape is stated in the plan because neither client's
 * expectation matched it</strong> — the web client wanted {@code {jobId, status}} and the mobile
 * client wanted a whole receipt. Neither is right: the row exists the moment the upload returns,
 * but its fields do not, and handing back an almost-empty receipt invites a client to render one.
 * The status says what to do next, and it is either {@code UPLOADED} (a pipeline is running) or
 * {@code COMPLETE} (there is no vision model, so the fields are the member's to fill in).
 *
 * <p>camelCase like every other body on this API: §5 item 4 removes the source DTOs'
 * {@code @JsonProperty("extracted_merchant")} snake_case overrides, which made receipts the one
 * inconsistent surface in the document.
 */
public record ReceiptUploadResponse(UUID id, ReceiptStatus status, Instant createdAt) {

    public static ReceiptUploadResponse of(Receipt receipt) {
        return new ReceiptUploadResponse(receipt.getId(), receipt.getStatus(),
            receipt.getCreatedAt());
    }
}
