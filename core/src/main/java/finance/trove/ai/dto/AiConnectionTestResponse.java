package finance.trove.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * {@code POST /v1/ai/settings/test} on success.
 *
 * <p>Every failure is an error response carrying one of the typed AI slugs instead, so
 * {@code ok} is always {@code true} here. It is on the wire anyway because a client that logs this
 * payload, or writes it into a store beside a previous failure, reads better with the flag present
 * than with its absence standing in for success.
 *
 * <p>{@code endpointHost} is the host alone — no scheme, no path, no query. It is enough for a
 * member to confirm they reached what they meant to, and it cannot carry a token the way a full
 * URL's query string can.
 *
 * @param endpointHost       the host that answered
 * @param checkedAt          when the test ran; also written to {@code lastTestAt}
 * @param modelCount         how many models the endpoint listed
 * @param visionModelPresent whether the configured vision model is among them, or null if none is
 *                           configured. A missing vision model is reported rather than refused —
 *                           receipts fall back to the manual path — where a missing chat model is
 *                           502 {@code ai-model-missing}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiConnectionTestResponse(
    boolean ok,
    String endpointHost,
    Instant checkedAt,
    int modelCount,
    Boolean visionModelPresent) {

    public static AiConnectionTestResponse passed(String endpointHost, Instant checkedAt,
                                                  int modelCount, Boolean visionModelPresent) {
        return new AiConnectionTestResponse(true, endpointHost, checkedAt, modelCount,
            visionModelPresent);
    }
}
