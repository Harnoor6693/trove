package finance.trove.ai.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One turn of {@link ChatCompletionRequest} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11).
 *
 * <p>{@code role} is the lower-case OpenAI-compatible spelling both clients already send, and it
 * is a plain string rather than an enum on purpose: an unknown role has to become a 400
 * {@code validation-error} naming the field, not a Jackson deserialisation failure that surfaces
 * as {@code malformed-request} and names nothing a client author can act on.
 *
 * <p>{@code system} is deliberately not accepted. The system turn is the server's own
 * construction — the coach's instructions plus the ledger context block — and a client that could
 * name it would be writing the prompt.
 *
 * @param role    {@code user} or {@code assistant}
 * @param content the text of the turn
 */
public record ChatTurnRequest(
    @NotBlank(message = "role is required")
    String role,

    @NotBlank(message = "content must not be blank")
    @Size(max = ChatCompletionRequest.MAX_CONTENT_CHARACTERS,
        message = "content must be at most " + ChatCompletionRequest.MAX_CONTENT_CHARACTERS
            + " characters")
    String content) {
}
