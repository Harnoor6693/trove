package finance.trove.ingestion.simplefin;

import finance.trove.common.domain.Money;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.ingestion.Amounts;
import finance.trove.ingestion.ParsedRow;
import finance.trove.ingestion.simplefin.dto.SimplefinAccount;
import finance.trove.ingestion.simplefin.dto.SimplefinTransaction;
import java.math.BigDecimal;
import java.time.Instant;
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
 * {@code transacted_at}, then {@code posted}, then <strong>nothing</strong>. The source fell back
 * to {@code LocalDate.now(UTC)} for a row with no {@code transacted}, which silently dated somebody
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
        BigDecimal amount = Amounts.parse(transaction.amount());
        // The protocol writes "posted": 0 on a pending row; the epoch is not a settlement day.
        Instant posted = transaction.posted() == null || transaction.posted().getEpochSecond() == 0
            ? null
            : transaction.posted();
        boolean pending = Boolean.TRUE.equals(transaction.pending()) || posted == null;
        Map<String, String> metadata = amount == null && transaction.amount() != null
            ? Map.of(ParsedRow.PARSE_ERROR, "the amount is not a number this server can store "
                + "(no exponent, at most " + Money.MAX_INTEGER_DIGITS + " digits before the "
                + "decimal point and " + Money.MAX_FRACTION_DIGITS + " after it)")
            : Map.of();
        return new RawTransaction(transaction.id(), account.id(),
            amount == null ? null : amount.abs(), blankToNull(account.currency()),
            transaction.description(), null, dateOf(transaction.transacted(), posted),
            pending ? null : posted,
            amount == null ? null : Amounts.direction(amount, transaction.description()), pending,
            metadata);
    }

    /** {@code transacted_at}, then {@code posted}, then nothing — never the day of the sync. */
    private static LocalDate dateOf(Instant transacted, Instant posted) {
        Instant when = transacted != null ? transacted : posted;
        return when == null ? null : LocalDate.ofInstant(when, ZoneOffset.UTC);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
