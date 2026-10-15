package finance.trove.notifications.webhook;

import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.notifications.NotificationProperties;
import finance.trove.notifications.webhook.dto.WebhookCreateRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three {@code /v1/webhooks} operations (§4.9).
 *
 * <p>Ownership is a query parameter, never a check: every read and the delete go through
 * {@code *AndUserId}, so a household mate's subscription simply matches nothing and becomes a 404.
 */
@Service
public class WebhookService {

    private final WebhookSubscriptionRepository subscriptions;
    private final OutboundUrlPolicy policy;
    private final FieldEncryptionService encryption;
    private final NotificationProperties properties;

    public WebhookService(WebhookSubscriptionRepository subscriptions, OutboundUrlPolicy policy,
                          FieldEncryptionService encryption,
                          NotificationProperties properties) {
        this.subscriptions = subscriptions;
        this.policy = policy;
        this.encryption = encryption;
        this.properties = properties;
    }

    /** Every subscription this member owns, oldest first. */
    @Transactional(readOnly = true)
    public List<WebhookSubscription> list(UUID userId) {
        return subscriptions.findAllByUserIdOrderByCreatedAtAsc(userId);
    }

    /**
     * Registers a subscription.
     *
     * <p>The URL goes through {@link OutboundUrlPolicy} — the shared validator that replaced the
     * source's {@code SafeWebhookUrlValidator} and adds the four ranges it missed
     * ({@code fc00::/7}, {@code 100.64.0.0/10}, {@code 0.0.0.0/8}, {@code ::ffff:0:0/96}). A
     * refusal is {@code OutboundUrlPolicyException}, which
     * {@code GlobalExceptionHandler} already renders as 400 {@code outbound-url-rejected}; no new
     * slug is invented for it.
     *
     * <p><strong>Passing here is not a promise the delivery will be attempted.</strong> The policy
     * runs again before every attempt, which is the half of defect D-N2 an annotation cannot do.
     * The caller is NOT opted in to private targets: a webhook is a member's own public endpoint,
     * and the two-key private exception exists for a LAN model rather than for arbitrary egress.
     *
     * <p>The cap is counted under a {@code PESSIMISTIC_WRITE} lock on the member's own
     * {@code users} row, so two creates racing the last slot produce one 201 and one 409 rather
     * than two subscriptions.
     *
     * @throws ConflictException when the member already holds the maximum number of subscriptions
     */
    @Transactional
    public WebhookSubscription create(UUID userId, WebhookCreateRequest request) {
        String url = request.targetUrl().trim();
        policy.check(url);

        // Before the count, not after it: without this lock the cap is a check-then-act and two
        // concurrent creates from one member both pass it. See lockOwner's javadoc.
        subscriptions.lockOwner(userId);
        long held = subscriptions.countByUserId(userId);
        if (held >= properties.maxWebhooksPerUser()) {
            throw new ConflictException("You already have " + properties.maxWebhooksPerUser()
                + " webhook subscriptions, which is the maximum. Delete one first.");
        }

        return subscriptions.save(new WebhookSubscription(userId, url,
            encryption.encrypt(request.secret()), request.eventTypesOrEmpty()));
    }

    /**
     * Deletes one of this member's subscriptions, and by cascade every delivery-log row that
     * belongs to it — including any still queued for retry, which is the point: a member who has
     * revoked an endpoint should not have events arriving at it for the next half minute.
     *
     * @throws ResourceNotFoundException when the id is not this member's, which is also the answer
     *                                   for a household mate's
     */
    @Transactional
    public void delete(UUID userId, UUID subscriptionId) {
        if (subscriptions.deleteByIdAndUserId(subscriptionId, userId) == 0) {
            throw ResourceNotFoundException.of("Webhook subscription", subscriptionId);
        }
    }
}
