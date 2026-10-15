package finance.trove.notifications.webhook.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import finance.trove.notifications.webhook.WebhookSubscription;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One of the caller's webhook subscriptions (§4.9).
 *
 * <p><strong>The shared signing secret is not here, in any form.</strong> Not masked, not
 * truncated, not as a length — a mask is a fingerprint and a length is a search-space reduction,
 * and neither buys a settings screen anything a boolean does not. {@code hasSecret} is the whole
 * of what this API says about it, exactly as {@code AiSettingsResponse.hasApiKey} is for the
 * per-member AI key. The spec's response schema is closed, so a future field that carried it would
 * fail the conformance check on every integration test rather than ship.
 *
 * @param hasSecret always true in this version — a secret is required at creation and can only be
 *                  removed by deleting the subscription. The field exists so that the shape does
 *                  not change the day an optional secret becomes possible, and so that no client
 *                  author goes looking for the secret itself here
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record WebhookResponse(
    UUID id,
    String targetUrl,
    boolean enabled,
    List<String> eventTypes,
    boolean hasSecret,
    Instant createdAt,
    Instant updatedAt) {

    public static WebhookResponse of(WebhookSubscription subscription) {
        return new WebhookResponse(
            subscription.getId(),
            subscription.getTargetUrl(),
            subscription.isEnabled(),
            List.copyOf(subscription.getEventTypes()),
            subscription.getSecretEncrypted() != null,
            subscription.getCreatedAt(),
            subscription.getUpdatedAt());
    }
}
