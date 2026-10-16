package finance.trove.receipts.receipt.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptSource;
import finance.trove.receipts.receipt.ReceiptStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A receipt in full (§4.10, §5 item 4).
 *
 * <p><strong>{@code thumbnailUrl} is a path on this API and nothing else</strong> —
 * {@code /api/v1/receipts/{id}/thumbnail}. Never an absolute URL, never a presigned link to a
 * storage system and never a {@code data:} URI: the bytes are behind an authenticated route like
 * everything else, and the mobile client's own type already says "server path; requires a bearer
 * token to fetch". Null when the row has no thumbnail image, which an upload this version accepted
 * always has.
 *
 * <p><strong>The three tax figures stay here.</strong> They are reverse-calculated for Canadian
 * members only and are never part of the tax export: the tax-time export carries the member's own
 * amounts and labels with no rate and no computed figure of any kind (§2.9, §4.12, open question
 * 1). The member data export includes the stored split.
 *
 * <p>camelCase throughout: §5 item 4 removes the source DTOs' snake_case
 * {@code @JsonProperty} overrides, which made receipts the one inconsistent surface in the
 * document.
 */
public record ReceiptResponse(UUID id, ReceiptStatus status, ReceiptSource source,
                              long uploadSizeBytes, String extractedMerchant,
                              BigDecimal extractedAmount, CurrencyCode extractedCurrency,
                              LocalDate extractedDate, BigDecimal gstAmount, BigDecimal pstAmount,
                              BigDecimal hstAmount, BigDecimal confidence, boolean reviewRequired,
                              UUID matchedTransactionId, String thumbnailUrl,
                              List<ReceiptLineItemResponse> lineItems, String errorMessage,
                              Instant processingStartedAt, Instant processingCompletedAt,
                              Instant createdAt, Instant updatedAt) {

    /** The path template the thumbnail lives at. The context path is {@code /api}. */
    private static final String THUMBNAIL_PATH = "/api/v1/receipts/%s/thumbnail";

    public static ReceiptResponse of(Receipt receipt) {
        String thumbnailUrl = receipt.thumbnail()
            .map(image -> THUMBNAIL_PATH.formatted(receipt.getId()))
            .orElse(null);
        return new ReceiptResponse(receipt.getId(), receipt.getStatus(), receipt.getSource(),
            receipt.getUploadSizeBytes(), receipt.getExtractedMerchant(),
            receipt.getExtractedAmount(), receipt.getExtractedCurrency(),
            receipt.getExtractedDate(), receipt.getGstAmount(), receipt.getPstAmount(),
            receipt.getHstAmount(), receipt.getConfidence(), receipt.isReviewRequired(),
            receipt.getMatchedTransactionId(), thumbnailUrl,
            receipt.getLineItems().stream().map(ReceiptLineItemResponse::of).toList(),
            receipt.getErrorMessage(), receipt.getProcessingStartedAt(),
            receipt.getProcessingCompletedAt(), receipt.getCreatedAt(), receipt.getUpdatedAt());
    }
}
