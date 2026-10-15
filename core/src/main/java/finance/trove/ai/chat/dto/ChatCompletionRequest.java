package finance.trove.ai.chat.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * What {@code POST /v1/chat/completions} takes ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11's
 * pinned wire contract: {@code {messages:[{role,content}]}}).
 *
 * <p><strong>The last entry is the question, and the rest are ignored.</strong> The history the
 * model sees is rebuilt from the server's own store for the conversation named by
 * {@code X-Session-Id}; a client cannot contribute to it. That is a security decision rather than
 * a simplification: accepting a client-supplied {@code assistant} turn would let a page put words
 * into the coach's mouth and then quote them back as the coach's own, which is the prompt-injection
 * shape that matters most for a feature whose output is rendered to a human as advice about their
 * money.
 *
 * <p>There is no {@code userId} field, and there never may be: {@code ArchitectureTest} bans one on
 * a request DTO and {@code SourceConventionTest} bans reading {@code X-User-Id}. The caller is the
 * signed principal.
 *
 * @param messages the turns the client is sending, oldest first
 */
public record ChatCompletionRequest(
    @NotNull(message = "messages is required")
    @Size(min = 1, max = ChatCompletionRequest.MAX_MESSAGES,
        message = "messages must carry between 1 and " + ChatCompletionRequest.MAX_MESSAGES
            + " turns")
    @Valid
    List<ChatTurnRequest> messages) {

    /**
     * The most turns one request may carry, matching {@code ChatCompletionRequest.messages
     * .maxItems} in {@code openapi/trove-core.yaml}. A constant rather than a property because it
     * has to be readable from an annotation, and one number in one place cannot disagree with the
     * contract.
     */
    public static final int MAX_MESSAGES = 20;

    /** The longest single question, matching {@code ChatTurnRequest.content.maxLength}. */
    public static final int MAX_CONTENT_CHARACTERS = 4_000;

    /** The turn the server will actually answer: the last one. Null when there are none. */
    public ChatTurnRequest lastTurn() {
        return messages == null || messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }
}
