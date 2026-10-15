package finance.trove.receipts.receipt.dto;

import jakarta.validation.constraints.NotNull;

/**
 * The reviewer's verdict — {@code PATCH /v1/receipts/{id}/review} (§4.10).
 *
 * <p>The source's version carried a {@code Map<String, String> corrections} whose keys were the
 * snake_case column names and whose values were strings the service re-parsed. That is gone:
 * corrections are a PATCH of typed fields on {@code PATCH /v1/receipts/{id}}, and this operation
 * records a decision. One body that both edits and decides is two operations wearing one name.
 *
 * <p>{@code accepted: true} clears {@code reviewRequired}; {@code false} marks the receipt
 * {@code FAILED} — the member saying the extraction is not worth correcting. The image stays
 * stored and downloadable either way.
 *
 * <p>A boxed {@link Boolean} with {@code @NotNull} rather than a primitive: a primitive would
 * silently default a missing field to {@code false} and fail a receipt the member never rejected.
 */
public record ReceiptReviewRequest(@NotNull Boolean accepted) {
}
