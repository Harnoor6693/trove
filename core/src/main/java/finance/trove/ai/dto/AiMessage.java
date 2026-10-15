package finance.trove.ai.dto;

/**
 * One turn of a conversation, in the OpenAI-compatible shape every endpoint this client talks to
 * understands.
 *
 * <p>Part of the {@link finance.trove.ai.AiGateway} seam rather than of any HTTP contract: collapse
 * steps 13 (coach), 14 (receipts) and 17 (categorisation) build lists of these and hand them to the
 * gateway. It is deliberately not the wire record — the wire record can change with a provider's
 * quirks without every caller in the monolith changing with it.
 *
 * @param role    {@code system}, {@code user} or {@code assistant}
 * @param content the text of the turn
 */
public record AiMessage(String role, String content) {

    public static AiMessage system(String content) {
        return new AiMessage("system", content);
    }

    public static AiMessage user(String content) {
        return new AiMessage("user", content);
    }

    public static AiMessage assistant(String content) {
        return new AiMessage("assistant", content);
    }
}
