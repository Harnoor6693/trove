package finance.trove.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One model the caller's endpoint serves — the response shape of {@code GET /v1/ai/models}, and
 * what {@link finance.trove.ai.client.OpenAiCompatibleClient} maps the endpoint's own list onto.
 *
 * <p>Narrowed to three fields on purpose. This is third-party output arriving from a host the
 * member chose, and passing it through unchanged would put an arbitrary JSON object from an
 * unvetted server into both clients' generated types. The response schema is closed, so anything
 * else the endpoint sends is dropped here and the contract stays the contract.
 *
 * @param id      the model id, sent back as {@code model} in the settings
 * @param ownedBy the endpoint's {@code owned_by}, when it sets one
 * @param created the endpoint's {@code created}, epoch seconds, when it sets one
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiModel(String id, String ownedBy, Long created) {
}
