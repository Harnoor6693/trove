package finance.trove.common.plugin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * One transaction as a {@link BankSource} reports it, before deduplication decides what to do
 * with it ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 M0;
 * {@code docs/recovery/01-FINDINGS.md} O7).
 *
 * <p><strong>Two changes from the {@code libs/common} record, and both are the same bug.</strong>
 * The old record had two identifier slots: {@code sourceId}, which the deduplication service used
 * as its exact-match key, and {@code id}, which nothing ever read. Every normaliser filled
 * {@code sourceId} with the plugin's own NAME — a constant, identical on every row — and put the
 * bank's real per-transaction id in {@code id}. With a unique index on
 * {@code (account_id, source_id)} that means at most ONE transaction per account could ever be
 * stored: the second row of the first sync collided with the first and was silently dropped
 * (O7/M0, blocker).
 *
 * <p>So {@code sourceId} is renamed {@link #providerTransactionId}, which says what it must hold,
 * and the unread {@code id} slot is <em>removed entirely</em> rather than left as a second place
 * to put the right value in. A record with one identifier cannot be filled in the wrong one.
 *
 * @param providerTransactionId the identifier the SOURCE gave this transaction — SimpleFIN's
 *                              {@code tx.id}, OFX's {@code FITID}, a CSV row's deterministic
 *                              hash. {@code null} only for a source that genuinely offers no
 *                              stable id, which puts the row on the fuzzy path instead
 * @param accountId             the provider's own id for the account, as the source knows it
 * @param amount                the absolute value; direction is {@link #type}
 * @param currency              ISO 4217; {@code null} means "the account's", never a literal
 * @param description           the raw text the source supplied
 * @param merchantName          a cleaner merchant string where the source offers one
 * @param date                  the calendar date the source reported
 * @param postedAt              when it settled; {@code null} while {@link #pending}
 * @param type                  what kind of movement it is; {@code null} means "unknown", which
 *                              the ingestion side rejects rather than guessing
 * @param pending               authorised but not yet settled
 * @param metadata              source-specific fields with no column of their own; never
 *                              {@code null}, and copied defensively
 */
public record RawTransaction(String providerTransactionId, String accountId, BigDecimal amount,
                             String currency, String description, String merchantName,
                             LocalDate date, Instant postedAt, TransactionType type,
                             boolean pending, Map<String, String> metadata) {

    public RawTransaction {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience for a source that has nothing extra to say. */
    public static RawTransaction of(String providerTransactionId, String accountId,
                                    BigDecimal amount, String currency, String description,
                                    String merchantName, LocalDate date, Instant postedAt,
                                    TransactionType type, boolean pending) {
        return new RawTransaction(providerTransactionId, accountId, amount, currency, description,
            merchantName, date, postedAt, type, pending, Map.of());
    }

    /** Whether this row can be matched by the exact key rather than by the fuzzy window. */
    public boolean hasProviderId() {
        return providerTransactionId != null && !providerTransactionId.isBlank();
    }

    @Override
    public String toString() {
        // Deliberately not the whole record: `description` and `merchantName` are a household's
        // spending history, and this object is logged on every per-row ingestion failure.
        return "RawTransaction[providerTransactionId=" + providerTransactionId + ", accountId="
            + accountId + ", date=" + date + ", pending=" + pending + "]";
    }

    /** Value equality including the metadata map, which {@link Objects} handles for us. */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RawTransaction other)) {
            return false;
        }
        return pending == other.pending
            && Objects.equals(providerTransactionId, other.providerTransactionId)
            && Objects.equals(accountId, other.accountId)
            && (amount == null ? other.amount == null
                : other.amount != null && amount.compareTo(other.amount) == 0)
            && Objects.equals(currency, other.currency)
            && Objects.equals(description, other.description)
            && Objects.equals(merchantName, other.merchantName)
            && Objects.equals(date, other.date)
            && Objects.equals(postedAt, other.postedAt)
            && type == other.type
            && Objects.equals(metadata, other.metadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerTransactionId, accountId,
            amount == null ? null : amount.stripTrailingZeros(), currency, description,
            merchantName, date, postedAt, type, pending, metadata);
    }
}
