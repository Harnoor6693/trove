package finance.trove.transactions.dto;

import finance.trove.common.plugin.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * {@code POST /v1/transactions} body — hand-entered rows only
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4; gap P2-13).
 *
 * <p>No {@code currency} (it is the account's), no {@code pending} and no
 * {@code providerTransactionId}: all three belong to a source, and accepting them here would let
 * a hand-entered row impersonate a fetched one — which the deduplicator would then reconcile
 * away on the next sync.
 *
 * @param amount the absolute value; {@link #type} carries the direction, which is the convention
 *               every ingested row already uses. {@code @DecimalMin} excludes zero because a
 *               zero-value transaction is a data-entry slip, not a fact about money
 * @param type   {@code null} means {@link TransactionType#DEBIT} — money out is what somebody
 *               reaching for a quick-add sheet is nearly always recording
 */
public record ManualTransactionRequest(@NotNull UUID accountId,
                                       @NotNull @DecimalMin(value = "0.0001")
                                       @Digits(integer = 15, fraction = 4) BigDecimal amount,
                                       @NotNull LocalDate date,
                                       TransactionType type,
                                       @Size(max = 255) String merchant,
                                       UUID categoryId,
                                       @Size(max = 2000) String notes) {

    /** The direction to store, defaulting rather than failing on an omitted {@code type}. */
    public TransactionType typeOrDefault() {
        return type == null ? TransactionType.DEBIT : type;
    }
}
