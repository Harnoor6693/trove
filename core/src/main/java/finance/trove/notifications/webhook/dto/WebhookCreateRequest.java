package finance.trove.notifications.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * The body of {@code POST /v1/webhooks} (§4.9).
 *
 * <p>{@code targetUrl} carries only length and blankness constraints here. The real check is
 * {@code OutboundUrlPolicy.check}, called in the service, and it is deliberately NOT a Bean
 * Validation annotation: the source's {@code @SafeWebhookUrl} ran once, on {@code @Valid} at
 * creation time, and that is precisely defect D-N2 — a URL vetted only at save time is a URL whose
 * host can start resolving somewhere else afterwards. The policy runs again before every delivery,
 * which an annotation on a request DTO cannot do.
 *
 * <p>{@code secret} is write-only: it is encrypted at rest and appears in no response.
 *
 * @param targetUrl  an absolute https URL
 * @param secret     the shared HMAC secret, 16 to 128 characters. Sixteen is a floor rather than a
 *                   style: a shorter shared secret is guessable offline from a single captured
 *                   delivery, and the signature would then prove nothing
 * @param eventTypes which events to deliver; null or empty means every event this member's ledger
 *                   raises
 */
public record WebhookCreateRequest(
    @NotBlank @Size(max = 2000) String targetUrl,
    @NotBlank @Size(min = 16, max = 128) String secret,
    @Size(max = 20) List<@NotBlank @Size(max = 50) String> eventTypes) {

    /** Never null, so callers need no second null check. */
    public List<String> eventTypesOrEmpty() {
        return eventTypes == null ? List.of() : List.copyOf(eventTypes);
    }
}
