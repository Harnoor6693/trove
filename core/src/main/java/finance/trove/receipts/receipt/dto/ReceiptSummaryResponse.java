package finance.trove.receipts.receipt.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.receipts.receipt.ReceiptSource;
import finance.trove.receipts.receipt.ReceiptStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of {@code GET /v1/receipts} (§4.10).
 *
 * <p><strong>Built by a JPQL constructor projection, not from a {@link
 * finance.trove.receipts.receipt.Receipt}.</strong> {@code Receipt} eagerly fetches its images and
 * its line items — which every other route needs and which the pipeline could not run without —
 * and materialising a page of two hundred of them would be four hundred collection selects for a
 * list that shows none of it. The projection reads one row per receipt and touches neither
 * collection.
 *
 * <p>The mobile client currently expects a bare array from this route and is wrong: every response
 * on this API is the {@code ApiResponse} envelope and this list is paged, with its totals in
 * {@code meta}. Step 21b regenerates its types.
 */
public record ReceiptSummaryResponse(UUID id, ReceiptStatus status, ReceiptSource source,
                                     String extractedMerchant, BigDecimal extractedAmount,
                                     CurrencyCode extractedCurrency, LocalDate extractedDate,
                                     BigDecimal confidence, boolean reviewRequired,
                                     Instant createdAt) {
}
