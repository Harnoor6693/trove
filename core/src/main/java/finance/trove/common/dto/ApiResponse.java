package finance.trove.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Objects;

/**
 * The uniform envelope around every HTTP response the monolith returns.
 *
 * <p>One shape for success and failure — {@code success}, a nullable {@code data}, a nullable
 * {@link ErrorResponse}, and an optional {@link PaginationMeta} — is what lets the web and
 * mobile clients unwrap a response without branching on the status code first. The three static
 * factories are the only way to build one, so a response can never claim success while carrying
 * an error, or carry pagination metadata it did not page.
 *
 * @param <T> the payload type; {@code Void} for an empty success
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, ErrorResponse error, PaginationMeta meta) {

    /** A single-item or empty success. */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    /** A page of results plus its metadata. */
    public static <T> ApiResponse<T> paged(T data, PaginationMeta meta) {
        Objects.requireNonNull(meta, "meta cannot be null for a paged response");
        return new ApiResponse<>(true, data, null, meta);
    }

    /** A failure; {@code data} is always {@code null}. */
    public static <T> ApiResponse<T> error(ErrorResponse error) {
        Objects.requireNonNull(error, "error cannot be null");
        return new ApiResponse<>(false, null, error, null);
    }
}
