package finance.trove.accounts;

/**
 * The financial product a {@link ConnectedAccount} is, for display and reporting
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V18).
 *
 * <p>Unchanged from the source enum, but the database CHECK is not: the pre-collapse
 * {@code connected_account.account_type} CHECK allowed {@code 'CREDIT'} while this enum has
 * always spelled it {@code CREDIT_CARD}, so every credit-card account the old service tried to
 * insert violated its own constraint. V18 lists these five names exactly.
 */
public enum AccountType {

    /** A day-to-day chequing/checking account. */
    CHECKING,

    /** A savings account. */
    SAVINGS,

    /** A credit card. Spelled to match the enum, not the old CHECK's {@code CREDIT}. */
    CREDIT_CARD,

    /** A brokerage or registered investment account. */
    INVESTMENT,

    /** Anything else, including the cash "account" a manual ledger usually is. */
    OTHER
}
