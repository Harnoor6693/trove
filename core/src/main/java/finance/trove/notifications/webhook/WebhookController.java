package finance.trove.notifications.webhook;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.dto.ApiResponse;
import finance.trove.notifications.webhook.dto.WebhookCreateRequest;
import finance.trove.notifications.webhook.dto.WebhookResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A member's outbound webhook subscriptions (§4.9).
 *
 * <p>Three routes, all on the caller's own rows. The identity comes from
 * {@code @AuthenticationPrincipal} and no path variable, query parameter or header here names a
 * user; a household mate's subscription id is 404, never 403.
 *
 * <p>The signing secret goes in through {@code POST} and never comes back out: no response type in
 * this package has a field for it, and the spec's response schemas are closed.
 */
@RestController
@RequestMapping("/v1/webhooks")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /** {@code GET /v1/webhooks} — the caller's subscriptions, oldest first. */
    @GetMapping
    public ApiResponse<List<WebhookResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal) {

        return ApiResponse.ok(webhooks.list(principal.getUserId()).stream()
            .map(WebhookResponse::of).toList());
    }

    /** {@code POST /v1/webhooks} — 201, matching every other create on this API. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WebhookResponse> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody WebhookCreateRequest request) {

        return ApiResponse.ok(
            WebhookResponse.of(webhooks.create(principal.getUserId(), request)));
    }

    /**
     * {@code DELETE /v1/webhooks/{id}}.
     *
     * <p>200 with the empty envelope rather than 204, matching
     * {@code DELETE /v1/categories/{id}} and {@code DELETE /v1/networth/assets/{id}}.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {

        webhooks.delete(principal.getUserId(), id);
        return ApiResponse.ok(null);
    }
}
