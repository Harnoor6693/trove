package finance.trove.transactions.dto;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import finance.trove.transactions.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A transaction as every route in this package returns it.
 *
 * <p>{@code amount} reaches the wire as a decimal <strong>string</strong> — the global
 * {@code BigDecimal} serializer in {@code TroveJsonModule} sees to that for every monetary field
 * in the application (§5.4), so a client cannot parse a ledger figure into an IEEE-754 double.
 *
 * @param createdBy the member whose action produced the row, for audit; never the scope
 */
public record TransactionResponse(UUID id, UUID householdId, UUID accountId, UUID createdBy,
                                  String providerTransactionId, BigDecimal amount,
                                  CurrencyCode currency, TransactionType type, String description,
                                  String merchantName, LocalDate date, Instant postedAt,
                                  boolean pending, boolean manual, UUID categoryId,
                                  UUID transferGroupId, UUID importBatchId, List<String> tags,
                                  String notes, Instant createdAt, Instant updatedAt) {

    public static TransactionResponse of(Transaction transaction) {
        return new TransactionResponse(transaction.getId(), transaction.getHouseholdId(),
            transaction.getAccountId(), transaction.getUserId(),
            transaction.getProviderTransactionId(), transaction.getAmount(),
            transaction.getCurrency(), transaction.getType(), transaction.getDescription(),
            transaction.getMerchantName(), transaction.getDate(), transaction.getPostedAt(),
            transaction.isPending(), transaction.isManual(), transaction.getCategoryId(),
            transaction.getTransferGroupId(), transaction.getImportBatchId(),
            transaction.getTags(), transaction.getNotes(), transaction.getCreatedAt(),
            transaction.getUpdatedAt());
    }
}
