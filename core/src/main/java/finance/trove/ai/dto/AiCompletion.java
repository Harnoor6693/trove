package finance.trove.ai.dto;

/**
 * What a non-streaming AI call returned — the seam's answer to steps 13, 14 and 17.
 *
 * <p>The token counts are what the endpoint reported and may be null: several OpenAI-compatible
 * servers omit {@code usage} entirely. They exist because {@code ai_audit_log} records them, not
 * because anything branches on them.
 *
 * @param content         the assistant's text; never null, possibly empty
 * @param model           the model the endpoint says it used, which need not be the one asked for
 * @param promptTokens    the endpoint's {@code usage.prompt_tokens}, or null
 * @param completionTokens the endpoint's {@code usage.completion_tokens}, or null
 */
public record AiCompletion(String content, String model, Integer promptTokens,
                           Integer completionTokens) {
}
