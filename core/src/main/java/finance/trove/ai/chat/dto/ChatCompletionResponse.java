package finance.trove.ai.chat.dto;

import finance.trove.ai.chat.CoachRole;
import java.time.Instant;
import java.util.UUID;

/**
 * A whole answer, for {@code POST /v1/chat/completions} with {@code stream=false}.
 *
 * <p>Deliberately not OpenAI's {@code choices[]} shape. Nothing in Trove routes on a choice index,
 * exactly one answer is ever returned, and a client made to reach through two arrays to find it is
 * being asked to model a protocol it does not speak. The streaming body keeps the OpenAI delta
 * frames, because that is what both clients' SSE readers parse and §4.11 pins them.
 *
 * @param sessionId the conversation this answer belongs to; also on the {@code X-Session-Id} header
 * @param role      always {@link CoachRole#ASSISTANT}, so the shape matches a history entry
 * @param content   the model's text, control characters stripped and length capped. Rendered to a
 *                  human and never interpreted
 * @param model     what the endpoint says it used, which need not be what was asked for; null when
 *                  it reported none
 * @param createdAt when the answer was stored
 */
public record ChatCompletionResponse(UUID sessionId, CoachRole role, String content, String model,
                                     Instant createdAt) {
}
