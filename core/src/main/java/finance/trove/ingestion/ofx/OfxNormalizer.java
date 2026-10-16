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
 * The sign of {@code TRNAMT} decides, as before, and {@code TRNTYPE} refines it where it agrees
 * with the sign: a bank fee is a {@code FEE} and not merely a debit, and interest earned is
 * {@code INTEREST}. A transfer stays a {@code DEBIT} or a {@code CREDIT}, which is what the pairing
 * job of gap P2-14 pairs into a transfer; typing it {@code TRANSFER} here hid it from that job and
 * threw its direction away (B1-11). The raw {@code TRNTYPE} and {@code CHECKNUM} are kept in the
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

    /**
     * B1-11: {@code TRNTYPE} only refines a direction the sign agrees with. A fee reversal
     * ({@code FEE}, positive) is money coming back and interest charged ({@code INT}, negative) is
     * money going out. {@code XFER} keeps the sign's direction, because the pairing job pairs a
     * DEBIT with a CREDIT and never a TRANSFER, and a TRANSFER says nothing about which way the
     * money went.
     */
    private static TransactionType type(OfxTransaction transaction, BigDecimal amount) {
        String kind = transaction.type() == null
            ? ""
            : transaction.type().trim().toUpperCase(Locale.ROOT);
        if (("FEE".equals(kind) || "SRVCHG".equals(kind)) && amount.signum() < 0) {
            return TransactionType.FEE;
        }
        if ("INT".equals(kind) && amount.signum() >= 0) {
            return TransactionType.INTEREST;
        }
        return Amounts.direction(amount, transaction.name(), transaction.memo());
    }
}
