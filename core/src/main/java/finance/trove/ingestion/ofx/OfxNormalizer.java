package finance.trove.ingestion.ofx;

import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.Amounts;
import finance.trove.ingestion.ofx.dto.OfxAccount;
import finance.trove.ingestion.ofx.dto.OfxTransaction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Turns a parsed OFX transaction into the shape the ingestion pipeline stores
 * ({@code docs/recovery/01-FINDINGS.md} O7/M0, F4).
 *
 * <h2>Two fixes from the pre-collapse normaliser</h2>
 * <ul>
 *   <li><strong>{@code FITID} is the identifier.</strong> The source wrote a private constant
 *       {@code SOURCE_ID = "ofx"} into the deduplication slot and the real {@code FITID} into a
 *       field nothing read. With the unique index on
 *       {@code (account_id, provider_transaction_id)}, an OFX account could hold exactly ONE
 *       transaction, for ever: the second row of the first import matched the first and was
 *       discarded as a duplicate.</li>
 *   <li><strong>{@code DEFAULT_CURRENCY = "USD"} is gone.</strong> The source stamped every row
 *       USD whatever the file said, so this plugin could not produce a correct Canadian-dollar
 *       transaction at all (plugins.md §4.2). The currency now comes from the transaction's own
 *       {@code CURSYM}, then the document's {@code CURDEF}, and otherwise is left {@code null} —
 *       which the ingestion side reads as "the account's own", never a literal (finding F4).</li>
 * </ul>
 *
 * <h2>Direction</h2>
 * The sign of {@code TRNAMT} decides, as before, and {@code TRNTYPE} refines it where OFX is
 * unambiguous: a bank fee is a {@code FEE} and not merely a debit, interest is {@code INTEREST},
 * and a transfer is a {@code TRANSFER} so that the pairing job of gap P2-14 can find both legs and
 * neither is counted as spending. The raw {@code TRNTYPE} and {@code CHECKNUM} are kept in the
 * metadata map, as they were.
 */
@Component
public class OfxNormalizer {

    /** Maps {@code amount} and {@code account} onto one {@link RawTransaction}. */
    public RawTransaction normalize(OfxAccount account, OfxTransaction transaction) {
        BigDecimal amount = transaction.amount();
        LocalDate date = transaction.dateUser() != null
            ? transaction.dateUser()
            : transaction.datePosted();

        Map<String, String> metadata = new HashMap<>(2);
        metadata.put("ofxType", transaction.type() != null ? transaction.type() : "");
        metadata.put("checkNum", transaction.checkNum() != null ? transaction.checkNum() : "");

        String description = transaction.memo() != null ? transaction.memo() : transaction.name();
        return new RawTransaction(transaction.fitId(), account.accountId(),
            amount == null ? null : amount.abs(),
            currencyOf(account, transaction), description, transaction.name(), date,
            transaction.datePosted() == null
                ? null
                : transaction.datePosted().atStartOfDay(ZoneOffset.UTC).toInstant(),
            amount == null ? null : type(transaction, amount), false, metadata);
    }

    /**
     * The transaction's own {@code CURSYM}, then the document's {@code CURDEF}, then
     * {@code null} — which means "the account's", and never a literal (F4).
     */
    private static String currencyOf(OfxAccount account, OfxTransaction transaction) {
        if (transaction.currency() != null && !transaction.currency().isBlank()) {
            return transaction.currency().trim();
        }
        if (account.currency() != null && !account.currency().isBlank()) {
            return account.currency().trim();
        }
        return null;
    }

    private static TransactionType type(OfxTransaction transaction, BigDecimal amount) {
        String kind = transaction.type() == null
            ? ""
            : transaction.type().trim().toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "FEE", "SRVCHG" -> TransactionType.FEE;
            case "INT" -> TransactionType.INTEREST;
            case "XFER" -> TransactionType.TRANSFER;
            default -> Amounts.direction(amount, transaction.name(), transaction.memo());
        };
    }
}
