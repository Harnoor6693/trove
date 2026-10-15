package finance.trove.common.plugin;

/**
 * What kind of movement a transaction is, as every source normalises it
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V19).
 *
 * <p>Moved unchanged from {@code libs/common}. All seven values matter downstream and the
 * database CHECK on {@code transaction.type} now spans the same seven: the pre-collapse CHECK
 * allowed only {@code DEBIT} and {@code CREDIT} while this enum already had all of them, so a
 * legitimate {@code FEE}, {@code INTEREST} or {@code REFUND} from any source was rejected by the
 * database.
 *
 * <p>The sign lives here, not on the amount: {@code transaction.amount} is always the absolute
 * value, and this enum says which way the money went. Every source normalises onto that
 * convention so no consumer has to know an institution's own sign rules.
 */
public enum TransactionType {

    /** Money leaving the account — a purchase or a bill payment. */
    DEBIT,

    /** Money entering the account — a deposit or a direct deposit. */
    CREDIT,

    /** A leg of a movement between two accounts the household holds itself (gap P2-14). */
    TRANSFER,

    /** A bank or service charge. */
    FEE,

    /** Interest earned or charged. */
    INTEREST,

    /** A reversal of an earlier debit. A budget aggregate nets this against spend (§6 D1). */
    REFUND,

    /** Anything a source cannot map onto the values above. */
    OTHER
}
