package finance.trove.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

/**
 * The one error body the monolith ever emits — an RFC 7807 "problem details" payload, carried
 * inside {@link ApiResponse#error(ErrorResponse)}.
 *
 * <p>Per {@code docs/recovery/06-COLLAPSE-PLAN.md} §5.4 every field name is <strong>camelCase</strong>.
 * That is a deliberate wire-format change: the api-gateway is the one service where the deleted
 * {@code JacksonConfig} SNAKE_CASE strategy actually ran, so today's clients see
 * {@code error.field_errors} and will see {@code error.fieldErrors} from now on (§4.13).
 *
 * <p>{@code fieldErrors} is a <em>list</em> of {@code {field, message}} pairs rather than a map
 * of field to messages: a map cannot express two failures on the same field without the client
 * unwrapping an array anyway, and a list serialises identically for both clients.
 *
 * <p>{@code requestId} is the value {@code RequestIdMdcFilter} put in the MDC and echoed in the
 * {@code X-Request-Id} response header, so a user can quote one id from a red toast and an
 * operator can find every log line for that request. A stack trace, an exception class name, a
 * SQL fragment or a file path must never reach this type — {@code detail} for a 500 is a fixed
 * generic sentence and the real cause is logged against the same {@code requestId}.
 *
 * @param type       machine-readable URI for the error class, e.g.
 *                   {@code https://trove.finance/errors/not-found}
 * @param title      short human-readable summary, normally the HTTP reason phrase
 * @param status     the HTTP status code, mirroring the response status
 * @param detail     human-readable, safe to show in a UI; never carries internal detail
 * @param instance   the request URI the failure happened on
 * @param requestId  correlation id shared with the {@code X-Request-Id} response header
 * @param fieldErrors per-field validation failures; {@code null} unless the status is 400
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String requestId,
    List<FieldError> fieldErrors) {

    /** Namespace for {@link #type}; the slug identifies the failure, the status does not. */
    public static final String TYPE_BASE_URI = "https://trove.finance/errors/";

    public ErrorResponse {
        fieldErrors = fieldErrors == null ? null : List.copyOf(fieldErrors);
    }

    /**
     * One rejected input.
     *
     * @param field   the property path the client sent, e.g. {@code amount} or {@code account.id}
     * @param message why it was rejected, already localised by Bean Validation
     */
    public record FieldError(String field, String message) {
        public FieldError {
            Objects.requireNonNull(field, "field cannot be null");
        }
    }

    /** Builder for the handful of call sites that assemble this by hand. */
    public static Builder builder(int status) {
        return new Builder(status);
    }

    /** Mutable assembler; {@link #build()} produces the immutable record. */
    public static final class Builder {

        private final int status;
        private String type = TYPE_BASE_URI + "internal-server-error";
        private String title;
        private String detail;
        private String instance;
        private String requestId;
        private List<FieldError> fieldErrors;

        private Builder(int status) {
            this.status = status;
        }

        public Builder type(String slug) {
            this.type = TYPE_BASE_URI + slug;
            return this;
        }

        public Builder title(String value) {
            this.title = value;
            return this;
        }

        public Builder detail(String value) {
            this.detail = value;
            return this;
        }

        public Builder instance(String value) {
            this.instance = value;
            return this;
        }

        public Builder requestId(String value) {
            this.requestId = value;
            return this;
        }

        public Builder fieldErrors(List<FieldError> value) {
            this.fieldErrors = value == null || value.isEmpty() ? null : List.copyOf(value);
            return this;
        }

        public ErrorResponse build() {
            return new ErrorResponse(type, title, status, detail, instance, requestId, fieldErrors);
        }
    }
}
