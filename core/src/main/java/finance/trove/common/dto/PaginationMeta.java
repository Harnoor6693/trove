package finance.trove.common.dto;

import finance.trove.common.domain.PagedResult;

/**
 * Pagination metadata attached to a list response by
 * {@link ApiResponse#paged(Object, PaginationMeta)}.
 *
 * <p>Carrying the totals in the body rather than only in headers means the SPA can render page
 * controls from the same fetch that rendered the rows.
 *
 * @param total number of matching records across every page
 * @param page  zero-based index of this page
 * @param limit maximum items per page, as requested
 */
public record PaginationMeta(long total, int page, int limit) {

    public static PaginationMeta of(long total, int page, int limit) {
        return new PaginationMeta(total, page, limit);
    }

    /** Derives the metadata from a repository-level {@link PagedResult}. */
    public static PaginationMeta from(PagedResult<?> result) {
        return new PaginationMeta(result.totalElements(), result.page(), result.limit());
    }
}
