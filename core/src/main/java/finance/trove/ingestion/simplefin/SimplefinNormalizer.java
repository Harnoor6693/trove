package finance.trove.ingestion.simplefin;

import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.simplefin.dto.SimplefinAccount;
import finance.trove.ingestion.simplefin.dto.SimplefinTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns a bridge transaction into the shape the ingestion pipeline stores
 * ({@code docs/recovery/01-FINDINGS.md} O7/M0).
 *
 * <h2>The identifier</h2>
 * {@code tx.id()} goes into {@code provider_transaction_id}. The pre-collapse normaliser put the
 * literal {@code "simplefin"} there — the same value on every row — and the bank's real id into a
 * field nothing read. With the unique index on
 * {@code (account_id, provider_transaction_id)} that meant a SimpleFIN account could hold exactly
 * ONE transaction, for ever: the second row of the very first sync matched the first and was
 * discarded as a duplicate.
 *
 * <h2>The date</h2>
 * {@code transacted}, then {@code posted}, then <strong>nothing</strong>. The source fell back to
 * {@code LocalDate.now(UTC)} for a row with no {@code transacted}, which silently dated somebody
 * else's transaction to the day of the sync — wrong in a way no later reader could detect, and
 * wrong again on the next sync, producing a different row. A row with no date at all is refused by
 * {@code DeduplicationService} with a reason the member can see.
 *
 * <h2>The currency</h2>
 * From the ACCOUNT, which is SimpleFIN's own model, and left {@code null} when the bridge declares
 * none — which the ingestion side reads as "the account's own", never a literal (finding F4).
 */
@Component
public class SimplefinNormalizer {

    /** Maps one bridge transaction onto a {@link RawTransaction}. */
    public RawTransaction normalize(SimplefinAccount account, SimplefinTransaction transaction) {
        BigDecimal amount = transaction.amount();
        LocalDate date = dateOf(transaction);
        boolean pending = transaction.posted() == null;
        TransactionType type = amount == null || amount.signum() >= 0
            ? TransactionType.CREDIT
            : TransactionType.DEBIT;
        return new RawTransaction(transaction.id(), account.id(),
            amount == null ? null : amount.abs(), blankToNull(account.currency()),
            transaction.description(), null, date, transaction.posted(),
            amount == null ? null : type, pending, Map.of());
    }

    private static LocalDate dateOf(SimplefinTransaction transaction) {
        if (transaction.transacted() != null) {
            return LocalDate.ofInstant(transaction.transacted(), ZoneOffset.UTC);
        }
        if (transaction.posted() != null) {
            return LocalDate.ofInstant(transaction.posted(), ZoneOffset.UTC);
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
