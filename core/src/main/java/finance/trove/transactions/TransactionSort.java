package finance.trove.transactions;

import finance.trove.common.exception.ValidationException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The sort orders {@code GET /v1/transactions} accepts — an <strong>allowlist</strong>, not a
 * field expression ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4).
 *
 * <p>The pre-collapse query took {@code sort=field,direction} and passed both halves to
 * {@code Sort.by(...)}, so the query string chose a column name. This enum is the whole
 * vocabulary: the caller's string is matched against {@link #parameter()} and anything else is
 * 400, and the SQL fragment that reaches the database is {@link #orderByClause()} — a compile-time
 * constant. Nothing from the query string is ever concatenated into SQL.
 *
 * <p>Every order ends with {@code created_at DESC} as a tiebreaker. Without it two rows with the
 * same date (which is the common case, not an edge case) can come back in a different order on
 * two calls, so a client paging through a large ledger would see one row twice and another never.
 */
public enum TransactionSort {

    /** Oldest first. */
    DATE_ASC("date,asc", "t.date ASC, t.created_at DESC"),

    /** Newest first — the default. */
    DATE_DESC("date,desc", "t.date DESC, t.created_at DESC"),

    /** Smallest amount first. */
    AMOUNT_ASC("amount,asc", "t.amount ASC, t.created_at DESC"),

    /** Largest amount first. */
    AMOUNT_DESC("amount,desc", "t.amount DESC, t.created_at DESC");

    /** What a caller sends when it sends nothing. */
    public static final TransactionSort DEFAULT = DATE_DESC;

    private final String parameter;
    private final String orderByClause;

    TransactionSort(String parameter, String orderByClause) {
        this.parameter = parameter;
        this.orderByClause = orderByClause;
    }

    /** The literal query-string value, e.g. {@code date,desc}. */
    public String parameter() {
        return parameter;
    }

    /** A constant SQL fragment. Never built from caller input. */
    String orderByClause() {
        return orderByClause;
    }

    /** Every accepted value, for the error message and for the contract's enum. */
    public static List<String> accepted() {
        return Arrays.stream(values()).map(TransactionSort::parameter).toList();
    }

    /**
     * Resolves a query-string value.
     *
     * @param raw {@code null} or blank for {@link #DEFAULT}
     * @throws ValidationException for anything outside the allowlist — the caller is told, rather
     *                             than being silently given the default order for a sort it asked
     *                             for and did not get
     */
    public static TransactionSort from(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT;
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        for (TransactionSort candidate : values()) {
            if (candidate.parameter.equals(cleaned)) {
                return candidate;
            }
        }
        throw new ValidationException("sort must be one of " + accepted() + ", got '" + raw + "'");
    }
}
