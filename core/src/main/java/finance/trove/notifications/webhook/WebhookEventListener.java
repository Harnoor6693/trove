package finance.trove.notifications.webhook;

import finance.trove.common.domain.Money;
import finance.trove.common.events.BudgetThresholdCrossedEvent;
import finance.trove.common.events.ReceiptProcessedEvent;
import finance.trove.common.events.SubscriptionPriceChangedEvent;
import finance.trove.common.events.SyncCompletedEvent;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * The webhook fan-out: a domain event becomes queued deliveries for the members who subscribed to
 * it ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D3; collapse step 16, §7 row 16).
 *
 * <p>{@link WebhookDeliveryService}'s own javadoc names this class as the component that was
 * missing — "nothing publishes into this queue yet" — and this closes that.
 *
 * <h2>It schedules, and does nothing else</h2>
 * Every handler ends at {@link WebhookDeliveryService#scheduleDelivery}. No socket is opened here,
 * no signature is computed, no URL is resolved and no retry is counted. All of that is step 11's
 * retry job, deliberately: the delivery-time re-validation of {@code target_url} and the pinned
 * DNS resolver are how the SSRF that D-N2 found is closed, and a listener that dialled a receiver
 * itself would walk straight around them. The queue is the only way in.
 *
 * <h2>Two listeners, not one</h2>
 * The notification producers live in {@code NotificationEventListener} and stay there. Keeping the
 * webhook fan-out in its own bean is a decision rather than an accident: the two have different
 * recipients and different failure consequences, each gets its OWN {@code REQUIRES_NEW}
 * transaction so that a webhook row failing to queue cannot roll back a member's inbox row (or the
 * reverse), and merging them would mean editing a merged, reviewed step's class to add work it was
 * not written for. The rule is applied consistently: every event handled here is handled in its
 * own method on this one webhook bean.
 *
 * <h2>Who gets a delivery</h2>
 * <ul>
 *   <li><strong>{@code budget.threshold_crossed} — every active member of the household.</strong>
 *       A budget is the household's shared plan and any member's spending can cross it. Unlike the
 *       inbox, the per-member {@code notification_preference} threshold is NOT consulted: that
 *       number is about how often a person wants to be interrupted, and a machine endpoint a
 *       member registered explicitly for {@code budget.threshold_crossed} has already said what it
 *       wants. Filtering is the subscription's {@code event_types}, and only that.</li>
 *   <li><strong>{@code subscription.price_changed} and {@code sync.completed} — the member the
 *       event names.</strong> Both events carry the member they belong to; fanning them out would
 *       put one member's bank connection into another member's integration.</li>
 * </ul>
 *
 * <p>A SUCCESSFUL sync IS delivered here, where it is not notified. The two are different
 * questions: an inbox row per hourly sync saying nothing happened is noise a person stops reading,
 * whereas a subscriber who asked for {@code sync.completed} by name wants the completion, and the
 * outcome is in the payload for it to switch on.
 *
 * <h2>What the payload carries, and what it deliberately does not</h2>
 * Ids, enum names, counts and amounts. No merchant string, no bank-supplied failure text, no
 * description and no subject line: a delivery is an outbound HTTP request to an address a member
 * typed in, and the receiver can read the member's own API for the detail. That is the same rule
 * every listener in this step follows for its logs, applied to the wire.
 */
@Component
public class WebhookEventListener {

    /** The vocabulary the spec's {@code eventTypes} description already publishes. */
    static final String BUDGET_THRESHOLD_CROSSED = "budget.threshold_crossed";

    /** As above. */
    static final String SUBSCRIPTION_PRICE_CHANGED = "subscription.price_changed";

    /** As above. */
    static final String SYNC_COMPLETED = "sync.completed";

    /**
     * As above — already named in the spec's {@code eventTypes} description, which is free-form
     * prose rather than an enum, so this needed no contract change.
     */
    static final String RECEIPT_PROCESSED = "receipt.processed";

    private static final Logger LOG = LoggerFactory.getLogger(WebhookEventListener.class);

    private final WebhookSubscriptionRepository subscriptions;
    private final WebhookDeliveryService deliveries;
    private final UserRepository users;
    private final ObjectMapper objectMapper;

    public WebhookEventListener(WebhookSubscriptionRepository subscriptions,
                                WebhookDeliveryService deliveries, UserRepository users,
                                ObjectMapper objectMapper) {
        this.subscriptions = subscriptions;
        this.deliveries = deliveries;
        this.users = users;
        this.objectMapper = objectMapper;
    }

    /** An envelope crossed a threshold: every active member's subscriptions hear about it. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBudgetThresholdCrossed(BudgetThresholdCrossedEvent event) {
        Map<String, Object> payload = base(BUDGET_THRESHOLD_CROSSED, event.householdId());
        payload.put("envelopeId", event.envelopeId().toString());
        payload.put("categoryId", event.categoryId() == null ? null
            : event.categoryId().toString());
        payload.put("month", event.month().toString());
        payload.put("spent", money(event.spent()));
        payload.put("allocated", money(event.allocated()));
        payload.put("overspent", event.isOverspent());
        fanOut(BUDGET_THRESHOLD_CROSSED, householdMembers(event.householdId()), payload);
    }

    /** A detected recurring charge changed price: the member it is attributed to. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionPriceChanged(SubscriptionPriceChangedEvent event) {
        Map<String, Object> payload = base(SUBSCRIPTION_PRICE_CHANGED, event.householdId());
        payload.put("subscriptionId", event.subscriptionId().toString());
        payload.put("previousAmount", money(event.previousAmount()));
        payload.put("newAmount", money(event.newAmount()));
        fanOut(SUBSCRIPTION_PRICE_CHANGED, List.of(event.userId()), payload);
    }

    /**
     * A sync finished, whatever the outcome.
     *
     * <p><strong>{@code fallbackExecution = true}, for the same reason
     * {@code NotificationEventListener.onSyncCompleted} carries it.</strong>
     * {@code SyncOrchestrator} is deliberately not {@code @Transactional} — a sync must not hold a
     * database connection across a bank call — so every one of its short transactions has already
     * committed by the time it publishes, and there is no transaction synchronization active at
     * the publish point. Without this flag Spring drops the event silently, with no log and no
     * error, and this handler would be dead code.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSyncCompleted(SyncCompletedEvent event) {
        Map<String, Object> payload = base(SYNC_COMPLETED, event.householdId());
        payload.put("accountId", event.accountId().toString());
        payload.put("syncLogId", event.syncLogId() == null ? null : event.syncLogId().toString());
        payload.put("outcome", event.outcome().name());
        payload.put("newCount", event.newCount());
        payload.put("updatedCount", event.updatedCount());
        fanOut(SYNC_COMPLETED, List.of(event.userId()), payload);
    }

    /**
     * A receipt finished its pipeline — EVERY outcome, including a clean one (collapse step 18;
     * ⚙ 28, specified by step 16).
     *
     * <p>This is the deliberate difference from {@code NotificationEventListener}, which stays
     * silent on {@code COMPLETE}. A member's inbox is for things a person has to act on; a
     * webhook subscriber that named {@code receipt.processed} asked for the fact, and
     * {@code outcome} is in the payload for it to switch on. The webhook is not the inbox.
     *
     * <p><strong>{@code fallbackExecution = true}, and here it is load-bearing rather than
     * cautious.</strong> {@code ReceiptEventPublisher.publishAfterCommit} branches on
     * {@code TransactionSynchronizationManager.isSynchronizationActive()}: with no synchronization
     * active it publishes immediately, which is the ordinary case because
     * {@code ReceiptPipelineOrchestrator} calls it outside any transaction after its final save
     * has committed. Without this flag Spring drops that event silently, with no log and no error,
     * and this handler is dead code — the same defect step 11's review found in
     * {@code SYNC_FAILED}. {@code ReceiptSeamIT} fails if the flag is removed.
     *
     * <p>The payload is ids and enum names and nothing else: no merchant string, no extracted
     * amount, no error text. A delivery is an outbound HTTP request to an address a member typed
     * in, and the receiver can read the member's own API for the detail — which is the rule every
     * handler on this bean follows, applied to a dataset that happens to be photographs of
     * somebody's shopping.
     *
     * <p><strong>A dormant member is not an audience.</strong> This is the one handler here that
     * targets a named member and is published from a background pipeline that can finish AFTER
     * that member left: a receipt takes seconds to process and the orphan sweep re-runs stuck ones
     * hours later. Since collapse step 18 a deleted member's row — and every webhook subscription
     * on it — survives the restore grace, so without this check the endpoint of an account that
     * has just been deleted would keep receiving deliveries for a week, which is precisely what
     * {@code DELETE /v1/me}'s own contract promises it will not. {@link #householdMembers} has
     * always filtered on {@code is_active} for the household fan-out; this is the same rule
     * applied to a per-member one.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReceiptProcessed(ReceiptProcessedEvent event) {
        if (!isLive(event.userId(), event.householdId())) {
            return;
        }
        Map<String, Object> payload = base(RECEIPT_PROCESSED, event.householdId());
        payload.put("receiptId", event.receiptId().toString());
        payload.put("outcome", event.outcome().name());
        payload.put("matchedTransactionId", event.matchedTransactionId() == null ? null
            : event.matchedTransactionId().toString());
        fanOut(RECEIPT_PROCESSED, List.of(event.userId()), payload);
    }

    /**
     * Queues one delivery per matching subscription.
     *
     * <p>The event-type filter is {@link WebhookDeliveryService#scheduleDelivery}'s, not this
     * method's: it already refuses a subscription that is disabled, gone, or does not want this
     * type, and re-deciding it here would be a second copy of a rule that could drift.
     *
     * <p>A failure is swallowed per handler, never per subscription list: the member's write has
     * already committed and a webhook that could not be queued must not turn their sync into an
     * error. Only ids and counts are logged.
     */
    private void fanOut(String eventType, List<UUID> recipients, Map<String, Object> payload) {
        String body;
        try {
            body = objectMapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            LOG.error("Could not serialise the {} webhook payload; nothing was queued", eventType,
                e);
            return;
        }
        int queued = 0;
        for (UUID recipient : recipients) {
            for (WebhookSubscription subscription
                : subscriptions.findAllByUserIdAndEnabledTrue(recipient)) {
                try {
                    if (deliveries.scheduleDelivery(subscription.getId(), eventType, body)
                        .isPresent()) {
                        queued++;
                    }
                } catch (RuntimeException e) {
                    LOG.error("Could not queue a {} delivery for subscription {}", eventType,
                        subscription.getId(), e);
                }
            }
        }
        LOG.debug("{}: {} deliveries queued across {} recipient(s)", eventType, queued,
            recipients.size());
    }

    /**
     * Whether {@code userId} is still a live member of {@code householdId}.
     *
     * <p>Scoped by the household the event carries rather than looked up unscoped, which is the
     * tree's rule even where the id came from a trusted event.
     */
    private boolean isLive(UUID userId, UUID householdId) {
        return users.findByIdAndHouseholdId(userId, householdId)
            .filter(User::isActive)
            .isPresent();
    }

    /** Every active member of the household, in a stable order. */
    private List<UUID> householdMembers(UUID householdId) {
        List<UUID> recipients = new ArrayList<>();
        for (User member : users.findAllByHouseholdIdOrderByCreatedAtAsc(householdId)) {
            if (member.isActive()) {
                recipients.add(member.getId());
            }
        }
        return recipients;
    }

    /** The fields every payload carries. A {@code LinkedHashMap} so the wire order is stable. */
    private static Map<String, Object> base(String eventType, UUID householdId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", eventType);
        payload.put("householdId", householdId.toString());
        return payload;
    }

    /** An amount on the wire: the plain figure and the currency's name, never converted. */
    private static Map<String, Object> money(Money amount) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("amount", amount.amount().toPlainString());
        value.put("currency", amount.currency().name());
        return value;
    }
}
