package finance.trove.ai;

import finance.trove.ai.dto.AiConnectionTestResponse;
import finance.trove.ai.dto.AiModel;
import finance.trove.ai.dto.AiSettingsResponse;
import finance.trove.ai.dto.AiSettingsUpdateRequest;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Each member's own AI endpoint ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11, owner decision 9).
 *
 * <p>Five routes, and every one of them acts on the caller and only on the caller. The identity
 * comes from {@code @AuthenticationPrincipal} — a claim of a token this server signed — and there
 * is no path variable, query parameter or header anywhere in this class that names a user. That is
 * not a convention here but the tenancy model: {@code ai_settings} is keyed by the user id, an
 * administrator has no more access to a member's endpoint or API key than a stranger does, and
 * there is deliberately no route through which one could be built.
 *
 * <p><strong>The API key is write-only.</strong> It goes in through {@code PUT}, it is removed
 * through {@code DELETE .../api-key}, and it never comes back out: no response type in this
 * package has a field for it, and the spec's response schemas are closed, so an accidental one
 * would fail every integration test rather than ship.
 *
 * <p>{@code /v1/ai/models} and {@code /v1/ai/settings/test} both reach the member's own endpoint
 * over the network, and neither asks for {@code enabled}: both exist to be used <em>while</em>
 * configuring — a member picks a model from the real list and proves the connection works before
 * switching the feature on. {@code /v1/ai/models} asks only for an endpoint, because a model is
 * what it is there to help choose; {@code /v1/ai/settings/test} asks for both, because verifying
 * that the endpoint serves the chosen model is the test.
 */
@RestController
@RequestMapping("/v1/ai")
public class AiSettingsController {

    private final AiSettingsService settings;

    public AiSettingsController(AiSettingsService settings) {
        this.settings = settings;
    }

    /** {@code GET /v1/ai/settings} — the caller's own settings, or the defaults. */
    @GetMapping("/settings")
    public ApiResponse<AiSettingsResponse> read(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(settings.read(principal.getUserId()));
    }

    /** {@code PUT /v1/ai/settings} — a replace; returns the whole resource for the query cache. */
    @PutMapping("/settings")
    public ApiResponse<AiSettingsResponse> update(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody AiSettingsUpdateRequest request) {

        return ApiResponse.ok(settings.update(principal.getUserId(), request));
    }

    /**
     * {@code DELETE /v1/ai/settings/api-key} — forget the stored key.
     *
     * <p>Returns the empty success rather than the settings object: the only field that changed is
     * {@code hasApiKey}, and a client that has just called this knows what it is now.
     */
    @DeleteMapping("/settings/api-key")
    public ApiResponse<Void> deleteApiKey(@AuthenticationPrincipal TroveUserDetails principal) {
        settings.clearApiKey(principal.getUserId());
        return ApiResponse.ok(null);
    }

    /** {@code POST /v1/ai/settings/test} — reach the configured endpoint and say what happened. */
    @PostMapping("/settings/test")
    public ApiResponse<AiConnectionTestResponse> test(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(settings.testConnection(principal.getUserId()));
    }

    /** {@code GET /v1/ai/models} — the models the caller's own endpoint serves. */
    @GetMapping("/models")
    public ApiResponse<List<AiModel>> models(@AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(settings.models(principal.getUserId()));
    }
}
