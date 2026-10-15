package finance.trove.common.domain;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One page of query results plus the metadata a client needs to ask for the next one, without a
 * second count query.
 *
 * <p>The compact constructor validates and defensively copies, so a page cannot be mutated after
 * it is handed out and a negative page or a zero limit fails at the source rather than in a
 * repository query.
 *
 * @param <T> the element type of this page
 */
public record PagedResult<T>(List<T> content, long totalElements, int page, int limit) {

    public PagedResult {
        Objects.requireNonNull(content, "content cannot be null");
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0, got " + page);
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, got " + limit);
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must be >= 0, got " + totalElements);
        }
        content = List.copyOf(content);
    }

    public static <T> PagedResult<T> empty(int page, int limit) {
        return new PagedResult<>(List.of(), 0L, page, limit);
    }

    /** Total number of pages at this {@code limit}; {@code 0} when nothing matched. */
    public int totalPages() {
        return (int) ((totalElements + limit - 1) / limit);
    }

    public boolean hasNext() {
        return (long) (page + 1) * limit < totalElements;
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }

    /** Maps the entities of this page to DTOs while keeping the pagination metadata intact. */
    public <R> PagedResult<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper cannot be null");
        List<R> mapped = content.stream().<R>map(mapper).toList();
        return new PagedResult<>(mapped, totalElements, page, limit);
    }
}
