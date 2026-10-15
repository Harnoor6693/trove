package finance.trove.notifications.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.domain.Money;
import finance.trove.common.events.BudgetThresholdCrossedEvent;
import finance.trove.common.events.SubscriptionPriceChangedEvent;
import finance.trove.common.events.ReceiptProcessedEvent;
import finance.trove.common.events.SyncCompletedEvent;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.lang.reflect.Method;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.interceptor.TransactionAttribute;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who a webhook fan-out addresses, what slug it uses, and what its payload is allowed to carry —
 * collapse step 16 wire 4.
 *
 * <p>{@code WebhookSeamIT} proves the delivery row really lands and really respects the
 * subscription's filter. What can only be shown here is the negative space: that a household-scoped
 * event reaches every active member and a member-scoped one reaches exactly one, that a
 * deactivated member is skipped, that one subscription failing does not take the rest of the fan
 * out with it, and that the serialised body contains no merchant string and no bank-supplied
 * failure text.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebhookEventListenerTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID ENVELOPE = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID SYNC_LOG = UUID.randomUUID();
    private static final UUID SUBSCRIPTION = UUID.randomUUID();

    @Mock
    private WebhookSubscriptionRepository subscriptions;

    @Mock
    private WebhookDeliveryService deliveries;

    @Mock
    private UserRepository users;

    private WebhookEventListener listener;

    @BeforeEach
    void buildTheListener() {
        listener = new WebhookEventListener(subscriptions, deliveries, users,
            JsonMapper.builder().build());
        when(deliveries.scheduleDelivery(any(), anyString(), anyString()))
            .thenReturn(Optional.of(UUID.randomUUID()));
    }

    /** A member with one enabled subscription; returns the member's id. */
    private UUID memberWithAnEndpoint(boolean active, UUID subscriptionId) {
        UUID id = UUID.randomUUID();
        WebhookSubscription subscription = new WebhookSubscription(id,
            "https://example.test/hook", "cipher", List.of());
        ReflectionTestUtils.setField(subscription, "id", subscriptionId);
        when(subscriptions.findAllByUserIdAndEnabledTrue(id)).thenReturn(List.of(subscription));
        User user = new User(HOUSEHOLD, UserRole.MEMBER, id + "@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneOffset.UTC);
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "active", active);
        when(users.findByIdAndHouseholdId(id, HOUSEHOLD)).thenReturn(Optional.of(user));
        return id;
    }

    private void householdHas(UUID... memberIds) {
        List<User> members = java.util.Arrays.stream(memberIds).map(id -> {
            User user = new User(HOUSEHOLD, UserRole.MEMBER, id + "@trove.test", "Member",
                Jurisdiction.CA, CurrencyCode.CAD, ZoneOffset.UTC);
            ReflectionTestUtils.setField(user, "id", id);
            ReflectionTestUtils.setField(user, "active", actives.contains(id));
            return user;
        }).toList();
        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(HOUSEHOLD)).thenReturn(members);
    }

    private final java.util.Set<UUID> actives = new java.util.HashSet<>();

    private UUID activeMember(UUID subscriptionId) {
        UUID id = memberWithAnEndpoint(true, subscriptionId);
        actives.add(id);
        return id;
    }

    private UUID inactiveMember(UUID subscriptionId) {
        return memberWithAnEndpoint(false, subscriptionId);
    }

    private static BudgetThresholdCrossedEvent crossing(UUID actor) {
        return new BudgetThresholdCrossedEvent(HOUSEHOLD, actor, ENVELOPE, CATEGORY,
            YearMonth.of(2026, 9), Money.of("340.00", CurrencyCode.CAD),
            Money.of("400.00", CurrencyCode.CAD));
    }

    private String bodyOf(UUID subscriptionId, String eventType) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(deliveries).scheduleDelivery(eq(subscriptionId), eq(eventType), body.capture());
        return body.getValue();
    }

    // ------------------------------------------------------------------ budget threshold

    @Test
    @DisplayName("a budget crossing fans out to every ACTIVE member of the household")
    void aCrossingReachesEveryActiveMember() {
        UUID firstSubscription = UUID.randomUUID();
        UUID secondSubscription = UUID.randomUUID();
        UUID first = activeMember(firstSubscription);
        UUID second = activeMember(secondSubscription);
        householdHas(first, second);

        listener.onBudgetThresholdCrossed(crossing(first));

        verify(deliveries).scheduleDelivery(eq(firstSubscription),
            eq(WebhookEventListener.BUDGET_THRESHOLD_CROSSED), anyString());
        verify(deliveries).scheduleDelivery(eq(secondSubscription),
            eq(WebhookEventListener.BUDGET_THRESHOLD_CROSSED), anyString());
    }

    @Test
    @DisplayName("a deactivated member's endpoint is not fed")
    void aDeactivatedMemberIsSkipped() {
        UUID activeSubscription = UUID.randomUUID();
        UUID goneSubscription = UUID.randomUUID();
        UUID active = activeMember(activeSubscription);
        UUID gone = inactiveMember(goneSubscription);
        householdHas(active, gone);

        listener.onBudgetThresholdCrossed(crossing(active));

        verify(deliveries).scheduleDelivery(eq(activeSubscription), anyString(), anyString());
        verify(deliveries, never()).scheduleDelivery(eq(goneSubscription), anyString(),
            anyString());
    }

    @Test
    @DisplayName("the budget payload carries ids, the month and the two amounts, and nothing else")
    void theBudgetPayloadIsIdsAndAmounts() {
        UUID subscriptionId = UUID.randomUUID();
        UUID member = activeMember(subscriptionId);
        householdHas(member);

        listener.onBudgetThresholdCrossed(crossing(member));

        assertThat(bodyOf(subscriptionId, WebhookEventListener.BUDGET_THRESHOLD_CROSSED))
            .contains("\"event\":\"budget.threshold_crossed\"")
            .contains("\"envelopeId\":\"" + ENVELOPE + "\"")
            .contains("\"month\":\"2026-09\"")
            .contains("\"amount\":\"340.00\"")
            .contains("\"overspent\":false");
    }

    // ------------------------------------------------------------------ price change

    @Test
    @DisplayName("a price change reaches only the member the event names, and never the merchant "
        + "string")
    void aPriceChangeIsAddressedToOneMemberAndCarriesNoMerchant() {
        UUID subscriptionId = UUID.randomUUID();
        UUID owner = activeMember(subscriptionId);
        UUID houseMate = activeMember(UUID.randomUUID());
        householdHas(owner, houseMate);

        listener.onSubscriptionPriceChanged(new SubscriptionPriceChangedEvent(HOUSEHOLD, owner,
            SUBSCRIPTION, "NETFLIX CANADA", Money.of("16.49", CurrencyCode.CAD),
            Money.of("20.99", CurrencyCode.CAD)));

        String body = bodyOf(subscriptionId, WebhookEventListener.SUBSCRIPTION_PRICE_CHANGED);
        assertThat(body).contains("\"subscriptionId\":\"" + SUBSCRIPTION + "\"")
            .contains("\"amount\":\"20.99\"");
        assertThat(body)
            .as("a delivery is an outbound request to an address a member typed in; the receiver "
                + "reads the merchant from the member's own API")
            .doesNotContain("NETFLIX");
        verify(users, never()).findAllByHouseholdIdOrderByCreatedAtAsc(any());
    }

    // ------------------------------------------------------------------ sync completed

    @Test
    @DisplayName("a SUCCESSFUL sync is delivered — the webhook is not the inbox")
    void aSuccessfulSyncIsStillDelivered() {
        UUID subscriptionId = UUID.randomUUID();
        UUID owner = activeMember(subscriptionId);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, owner, ACCOUNT, SYNC_LOG,
            SyncCompletedEvent.Outcome.SUCCESS, 4, 1, null));

        assertThat(bodyOf(subscriptionId, WebhookEventListener.SYNC_COMPLETED))
            .contains("\"outcome\":\"SUCCESS\"")
            .contains("\"newCount\":4")
            .contains("\"updatedCount\":1");
    }

    @Test
    @DisplayName("the bank's own failure text is never put on the wire")
    void theFailureReasonIsNotDelivered() {
        UUID subscriptionId = UUID.randomUUID();
        UUID owner = activeMember(subscriptionId);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, owner, ACCOUNT, SYNC_LOG,
            SyncCompletedEvent.Outcome.FAILURE, 0, 0,
            "credentials rejected for account ****4417"));

        assertThat(bodyOf(subscriptionId, WebhookEventListener.SYNC_COMPLETED))
            .contains("\"outcome\":\"FAILURE\"")
            .doesNotContain("4417")
            .doesNotContain("credentials");
    }

    // ------------------------------------------------------------------ failure isolation

    @Test
    @DisplayName("one subscription failing to queue does not stop the rest of the fan-out")
    void oneBadSubscriptionDoesNotStopTheOthers() {
        UUID badSubscription = UUID.randomUUID();
        UUID goodSubscription = UUID.randomUUID();
        UUID first = activeMember(badSubscription);
        UUID second = activeMember(goodSubscription);
        householdHas(first, second);
        when(deliveries.scheduleDelivery(eq(badSubscription), anyString(), anyString()))
            .thenThrow(new IllegalStateException("the queue refused this row"));

        assertThatCode(() -> listener.onBudgetThresholdCrossed(crossing(first)))
            .doesNotThrowAnyException();
        verify(deliveries).scheduleDelivery(eq(goodSubscription), anyString(), anyString());
    }

    @Test
    @DisplayName("a member with no endpoint queues nothing at all")
    void noSubscriptionIsNoWork() {
        UUID member = UUID.randomUUID();
        actives.add(member);
        when(subscriptions.findAllByUserIdAndEnabledTrue(member)).thenReturn(List.of());
        householdHas(member);

        listener.onBudgetThresholdCrossed(crossing(member));

        verify(deliveries, never()).scheduleDelivery(any(), anyString(), anyString());
    }

    /**
     * The listener contract of §2, asserted through {@link AnnotationTransactionAttributeSource} —
     * the class the running container puts behind every {@code @Transactional} proxy — rather than
     * through {@code getAnnotation} alone, so what is asserted is what Spring would actually do.
     *
     * <p>{@code AFTER_COMMIT} because fanning a webhook out for a write that rolled back tells a
     * member's own server something that never happened; {@code REQUIRES_NEW} because after that
     * commit there is no transaction left to join and a plain {@code @Transactional} would open
     * none, silently, which is the one failure mode this whole step was warned about. Unlike
     * {@code CategorisationListener}, this listener holds no long round-trip: it only queues rows,
     * so the transaction it opens is short and wanted.
     */
    @Test
    @DisplayName("every handler is AFTER_COMMIT + REQUIRES_NEW, and the sync handler runs even "
        + "with no synchronization to hang off")
    void theAnnotationContractIsTheListenerContract() throws Exception {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();
        List<Method> handlers = List.of(
            WebhookEventListener.class.getMethod("onBudgetThresholdCrossed",
                BudgetThresholdCrossedEvent.class),
            WebhookEventListener.class.getMethod("onSubscriptionPriceChanged",
                SubscriptionPriceChangedEvent.class),
            WebhookEventListener.class.getMethod("onSyncCompleted", SyncCompletedEvent.class));

        for (Method handler : handlers) {
            TransactionalEventListener annotation =
                handler.getAnnotation(TransactionalEventListener.class);
            assertThat(annotation).as(handler.getName()).isNotNull();
            assertThat(annotation.phase()).as(handler.getName())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
            TransactionAttribute attribute =
                source.getTransactionAttribute(handler, WebhookEventListener.class);
            assertThat(attribute).as(handler.getName()).isNotNull();
            assertThat(attribute.getPropagationBehavior()).as(handler.getName())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        assertThat(handlers.get(2).getAnnotation(TransactionalEventListener.class)
            .fallbackExecution())
            .as("SyncOrchestrator publishes with no transaction synchronization active, and "
                + "without this flag Spring drops the event without a word")
            .isTrue();
        assertThat(handlers.get(0).getAnnotation(TransactionalEventListener.class)
            .fallbackExecution())
            .as("the other two are published from inside a transaction and must NOT fire when it "
                + "rolls back")
            .isFalse();
    }

    @Test
    @DisplayName("A receipt uploaded by a member who has since been deleted queues no delivery")
    void aDormantUploaderIsNotAnAudience() {
        // Collapse step 18. A receipt takes seconds to process and the orphan sweep re-runs stuck
        // ones hours later, so a pipeline run can finish AFTER its uploader deleted their account
        // — and the soft delete leaves both the user row and its webhook subscriptions in place
        // for the restore grace. Without the liveness check the endpoint of an account that has
        // just been deleted keeps receiving deliveries for a week, which is the opposite of what
        // DELETE /v1/me's own contract promises.
        UUID uploader = inactiveMember(SUBSCRIPTION);

        listener.onReceiptProcessed(new ReceiptProcessedEvent(HOUSEHOLD, uploader,
            UUID.randomUUID(), ReceiptProcessedEvent.Outcome.COMPLETE, null));

        verify(deliveries, never()).scheduleDelivery(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("onReceiptProcessed is AFTER_COMMIT + REQUIRES_NEW with fallbackExecution = true")
    void theReceiptHandlerCarriesTheFallback() throws Exception {
        // Collapse step 18, and the flag is load-bearing rather than defensive.
        // ReceiptEventPublisher.publishAfterCommit branches on isSynchronizationActive(), and the
        // ordinary case — ReceiptPipelineOrchestrator, which calls it outside any transaction
        // after its final save has committed — takes the "publish immediately" branch. With the
        // default fallbackExecution = false Spring drops that event SILENTLY, with no log and no
        // error, and this handler is dead code. That is the exact defect step 11's review found
        // in SYNC_FAILED, and ReceiptSeamIT drives the real pipeline so it stays found.
        Method handler = WebhookEventListener.class.getMethod("onReceiptProcessed",
            ReceiptProcessedEvent.class);

        TransactionalEventListener annotation =
            handler.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution())
            .as("without this the webhook consumer of receipt.processed never runs at all")
            .isTrue();

        TransactionAttribute attribute = new AnnotationTransactionAttributeSource()
            .getTransactionAttribute(handler, WebhookEventListener.class);
        assertThat(attribute).isNotNull();
        assertThat(attribute.getPropagationBehavior())
            .as("after the commit there is no transaction left to join")
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
