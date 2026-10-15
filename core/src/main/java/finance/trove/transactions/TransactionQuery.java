package finance.trove.transactions;

import finance.trove.common.exception.ValidationException;
import finance.trove.common.plugin.TransactionType;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The validated filter, sort and page of {@code GET /v1/transactions}
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.4; gap P2-15).
 *
 * <p>Built only through {@link #of}, which is where every bound is enforced, so a
 * {@code TransactionQuery} that exists is a query that is safe to run. The pre-collapse record
 * validated {@code limit} and nothing else, and accepted an arbitrary sort field.
 *
 * <p><strong>Out of range is rejected, not clamped.</strong> A client that computed
 * {@code limit=1000} asked for a thousand rows; quietly answering with five hundred and a
 * {@code meta} that says {@code limit: 500} is a page boundary the client did not choose and will
 * get wrong on its next request.
 *
 * @param page       zero-based
 * @param limit      1..{@value #MAX_LIMIT}; {@value #DEFAULT_LIMIT} when the caller sends none
 * @param sort       from the allowlist
 * @param from       inclusive lower bound on {@code date}, or {@code null}
 * @param to         inclusive upper bound on {@code date}, or {@code null}
 * @param type       one transaction type, or {@code null} for all
 * @param accountId  one of the household's accounts, or {@code null}
 * @param categoryId one category, or {@code null}
 * @param q          a case-insensitive substring of merchant, description or notes; {@code null}
 *                   when the caller sent nothing or sent only whitespace
 */
public record TransactionQuery(int page, int limit, TransactionSort sort, LocalDate from,
                               LocalDate to, TransactionType type, UUID accountId,
                               UUID categoryId, String q) {

    /** What a caller gets when it sends no {@code limit} — stated in the contract too. */
    public static final int DEFAULT_LIMIT = 50;

    /** The ceiling. An unbounded page would get slower for the whole install, for ever. */
    public static final int MAX_LIMIT = 500;

    /** The longest {@code q} accepted; a trigram search over more is not a search. */
    public static final int MAX_Q_LENGTH = 200;

    /** The first page of everything, newest first. */
    public static TransactionQuery unfiltered() {
        return new TransactionQuery(0, DEFAULT_LIMIT, TransactionSort.DEFAULT, null, null, null,
            null, null, null);
    }

    /**
     * Validates raw query-string values into a query.
     *
     * @throws ValidationException on a negative page, a limit outside its range, a sort outside
     *                             the allowlist, an over-long {@code q}, or a {@code from} after
     *                             its {@code to}
     */
    public static TransactionQuery of(Integer page, Integer limit, String sort, LocalDate from,
                                      LocalDate to, TransactionType type, UUID accountId,
                                      UUID categoryId, String q) {
        int resolvedPage = page == null ? 0 : page;
        if (resolvedPage < 0) {
            throw new ValidationException("page must be >= 0, got " + resolvedPage);
        }
        int resolvedLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (resolvedLimit < 1 || resolvedLimit > MAX_LIMIT) {
            throw new ValidationException(
                "limit must be between 1 and " + MAX_LIMIT + ", got " + resolvedLimit);
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from must not be after to");
        }
        String cleanedQ = q == null || q.isBlank() ? null : q.trim();
        if (cleanedQ != null && cleanedQ.length() > MAX_Q_LENGTH) {
            throw new ValidationException(
                "q must be at most " + MAX_Q_LENGTH + " characters, got " + cleanedQ.length());
        }
        return new TransactionQuery(resolvedPage, resolvedLimit, TransactionSort.from(sort), from,
            to, type, accountId, categoryId, cleanedQ);
    }

    /** Whether a free-text search is part of this query. */
    public boolean hasSearch() {
        return q != null;
    }

    /** Rows to skip — {@code long} because {@code page * limit} overflows an int at scale. */
    public long offset() {
        return (long) page * limit;
    }
}
