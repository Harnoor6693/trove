package finance.trove.ai.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import finance.trove.ai.AiSettings;
import finance.trove.ai.PrivacyTier;
import java.time.Instant;

/**
 * {@code GET /v1/ai/settings} and the echo of {@code PUT} (§4.11).
 *
 * <p><strong>The API key is not here, in any form.</strong> Not masked, not truncated, not as a
 * length. {@code hasApiKey} is the whole of what this response says about it, because a mask is a
 * fingerprint and a length is a search-space reduction, and neither buys the settings screen
 * anything a boolean does not. The spec's response schema is closed, so a future field that
 * accidentally carried it would fail the conformance check on every integration test rather than
 * ship.
 *
 * <p>{@code @JsonInclude(ALWAYS)}, so every field is on the wire even when null and the clients'
 * generated types can mark the nullable ones {@code | null} rather than optional. The spec's
 * {@code required} list matches this exactly.
 *
 * @param enabled              whether AI features are on for this member
 * @param endpointUrl          the OpenAI-compatible base URL, or null
 * @param hasApiKey            whether a key is stored; the key itself never leaves the server
 * @param model                the chat model id, or null
 * @param visionModel          the receipt-image model id, or null
 * @param allowPrivateEndpoint this member's half of the two-key private-target opt-in
 * @param privacyTier          how far this member lets their data travel
 * @param connectTimeoutMs     requested connect timeout; the effective one is capped on use
 * @param readTimeoutMs        requested read timeout; the effective one is capped on use
 * @param lastTestAt           when the connection was last tested, or null
 * @param lastTestOk           whether that test passed, or null if never run
 * @param updatedAt            when the row was last written, or null for the untouched defaults
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AiSettingsResponse(
    boolean enabled,
    String endpointUrl,
    boolean hasApiKey,
    String model,
    String visionModel,
    boolean allowPrivateEndpoint,
    PrivacyTier privacyTier,
    int connectTimeoutMs,
    int readTimeoutMs,
    Instant lastTestAt,
    Boolean lastTestOk,
    Instant updatedAt) {

    public static AiSettingsResponse of(AiSettings settings) {
        return new AiSettingsResponse(
            settings.isEnabled(),
            settings.getEndpointUrl(),
            settings.hasApiKey(),
            settings.getModel(),
            settings.getVisionModel(),
            settings.isAllowPrivateEndpoint(),
            settings.getPrivacyTier(),
            settings.getConnectTimeoutMs(),
            settings.getReadTimeoutMs(),
            settings.getLastTestAt(),
            settings.getLastTestOk(),
            settings.getUpdatedAt());
    }
}
