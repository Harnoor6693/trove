package finance.trove.notifications.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.domain.Money;
import finance.trove.common.events.BudgetThresholdCrossedEvent;
import finance.trove.common.events.ReceiptProcessedEvent;
import finance.trove.common.events.SubscriptionPriceChangedEvent;
import finance.trove.common.events.SyncCompletedEvent;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.preference.NotificationPreference;
import finance.trove.notifications.preference.NotificationPreferenceService;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The three event handlers: who is told, and under which key.
 *
 * <p>The keys are the substance. Each type's window is encoded IN its key so that V32's partial
 * unique index enforces the deduplication, rather than a "have we sent one recently?" read that
 * two listeners can both answer "no" to at the same instant.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationEventListenerTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID ENVELOPE = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID SUBSCRIPTION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T02:00:00Z");

    @Mock
    private NotificationService notifications;

    @Mock
    private NotificationPreferenceService preferences;

    @Mock
    private UserRepository users;

    private final Map<UUID, NotificationPreference> rows = new HashMap<>();
    private final Map<UUID, ZoneId> zones = new HashMap<>();
    private final Map<UUID, Boolean> actives = new HashMap<>();
    private NotificationEventListener listener;

    @BeforeEach
    void setUp() {
        UserTimeZoneProvider provider = zones::get;
        listener = new NotificationEventListener(notifications, preferences, users,
            new UserClock(provider, Clock.fixed(NOW, ZoneOffset.UTC)));
        when(preferences.getOrCreate(any())).thenAnswer(invocation ->
            rows.computeIfAbsent(invocation.getArgument(0), NotificationPreference::defaultsFor));
    }

    private UUID member(String zone, Short thresholdPercent, boolean active) {
        UUID id = UUID.randomUUID();
        NotificationPreference preference = NotificationPreference.defaultsFor(id);
        if (thresholdPercent != null) {
            preference.setBudgetAlertThresholdPercent(thresholdPercent);
        }
        rows.put(id, preference);
        zones.put(id, ZoneId.of(zone));
        actives.put(id, active);
        User user = new User(HOUSEHOLD, UserRole.MEMBER, id + "@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of(zone));
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "active", active);
        when(users.findByIdAndHouseholdId(id, HOUSEHOLD))
            .thenReturn(java.util.Optional.of(user));
        return id;
    }

    private void householdHas(UUID... memberIds) {
        List<User> members = java.util.Arrays.stream(memberIds).map(id -> {
            User user = new User(HOUSEHOLD, UserRole.MEMBER, id + "@trove.test", "Member",
                Jurisdiction.CA, CurrencyCode.CAD, zones.getOrDefault(id, ZoneOffset.UTC));
            ReflectionTestUtils.setField(user, "id", id);
            ReflectionTestUtils.setField(user, "active", actives.getOrDefault(id, true));
            return user;
        }).toList();
        when(users.findAllByHouseholdIdOrderByCreatedAtAsc(HOUSEHOLD)).thenReturn(members);
    }

    private static BudgetThresholdCrossedEvent crossing(UUID actor, String spent,
                                                        String allocated) {
        return new BudgetThresholdCrossedEvent(HOUSEHOLD, actor, ENVELOPE, CATEGORY,
            YearMonth.of(2026, 9), Money.of(spent, CurrencyCode.CAD),
            Money.of(allocated, CurrencyCode.CAD));
    }

    // ------------------------------------------------------------------ budget threshold

    @Test
    @DisplayName("BUDGET_THRESHOLD is keyed by envelope and month, so one envelope alerts once")
    void theBudgetKeyIsEnvelopeAndMonth() {
        UUID one = member("America/Toronto", null, true);
        householdHas(one);

        listener.onBudgetThresholdCrossed(crossing(one, "95.00", "100.00"));

        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);
        verify(notifications).notify(eq(one), eq(NotificationType.BUDGET_THRESHOLD),
            content.capture());
        assertThat(content.getValue().dedupKey()).isEqualTo(ENVELOPE + ":2026-09");
    }

    @Test
    @DisplayName("each member is told only when their OWN threshold is reached")
    void everyMemberIsJudgedAgainstTheirOwnThreshold() {
        // The step-8 swap's whole point. The envelope publishes when ANY member's ratio is
        // crossed; this handler decides which of them that ratio actually reached. With one
        // shared number the 95 % member would be alerted at 72 % of their budget.
        UUID early = member("America/Toronto", (short) 70, true);
        UUID late = member("America/Toronto", (short) 95, true);
        householdHas(early, late);

        listener.onBudgetThresholdCrossed(crossing(early, "72.00", "100.00"));

        verify(notifications).notify(eq(early), eq(NotificationType.BUDGET_THRESHOLD), any());
        verify(notifications, never()).notify(eq(late), any(), any());
    }

    @Test
    @DisplayName("the later crossing reaches the member the earlier one did not")
    void theSecondCrossingReachesTheHigherThreshold() {
        UUID early = member("America/Toronto", (short) 70, true);
        UUID late = member("America/Toronto", (short) 95, true);
        householdHas(early, late);

        listener.onBudgetThresholdCrossed(crossing(early, "96.00", "100.00"));

        // Both are notified on this one; the database, not this handler, is what stops `early`
        // being told twice — the dedup key is identical for both crossings.
        verify(notifications).notify(eq(early), eq(NotificationType.BUDGET_THRESHOLD), any());
        verify(notifications).notify(eq(late), eq(NotificationType.BUDGET_THRESHOLD), any());
    }

    @Test
    @DisplayName("a deactivated member is not notified")
    void aDeactivatedMemberIsSkipped() {
        UUID active = member("America/Toronto", null, true);
        UUID gone = member("America/Toronto", null, false);
        householdHas(active, gone);

        listener.onBudgetThresholdCrossed(crossing(active, "95.00", "100.00"));

        verify(notifications).notify(eq(active), any(), any());
        verify(notifications, never()).notify(eq(gone), any(), any());
    }

    @Test
    @DisplayName("an overspent envelope says so, and a merely-close one names the percentage")
    void theSubjectDistinguishesOverspentFromClose() {
        UUID one = member("America/Toronto", null, true);
        householdHas(one);
        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);

        listener.onBudgetThresholdCrossed(crossing(one, "120.00", "100.00"));
        verify(notifications).notify(eq(one), any(), content.capture());
        assertThat(content.getValue().subject()).contains("over by CAD 20.00");

        listener.onBudgetThresholdCrossed(crossing(one, "92.00", "100.00"));
        verify(notifications, org.mockito.Mockito.times(2))
            .notify(eq(one), any(), content.capture());
        assertThat(content.getValue().subject()).contains("92%");
        assertThat(content.getValue().body())
            .as("the currency is named and never converted")
            .contains("CAD 92.00").contains("CAD 100.00");
    }

    // ------------------------------------------------------------------ subscription

    @Test
    @DisplayName("SUBSCRIPTION_PRICE_CHANGE is keyed by subscription and the NEW amount")
    void theSubscriptionKeyIsTheNewPrice() {
        // A rescan that sees the same new price produces the identical key and is deduplicated;
        // a price that changes a second time produces a different one and is a second alert. A
        // timer could not tell those two apart.
        UUID owner = member("America/Toronto", null, true);
        householdHas(owner);
        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);

        listener.onSubscriptionPriceChanged(new SubscriptionPriceChangedEvent(HOUSEHOLD, owner,
            SUBSCRIPTION, "NETFLIX", Money.of("18.99", CurrencyCode.CAD),
            Money.of("22.99", CurrencyCode.CAD)));

        verify(notifications).notify(eq(owner), eq(NotificationType.SUBSCRIPTION_PRICE_CHANGE),
            content.capture());
        assertThat(content.getValue().dedupKey()).isEqualTo(SUBSCRIPTION + ":22.99");
        assertThat(content.getValue().subject()).isEqualTo("NETFLIX rose to CAD 22.99");
        assertThat(content.getValue().body()).contains("CAD 18.99").contains("CAD 4.00 more");
    }

    @Test
    @DisplayName("a price that fell is described as falling")
    void aPriceDropReadsAsADrop() {
        UUID owner = member("America/Toronto", null, true);
        householdHas(owner);
        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);

        listener.onSubscriptionPriceChanged(new SubscriptionPriceChangedEvent(HOUSEHOLD, owner,
            SUBSCRIPTION, "SPOTIFY", Money.of("14.99", CurrencyCode.CAD),
            Money.of("11.99", CurrencyCode.CAD)));

        verify(notifications).notify(eq(owner), any(), content.capture());
        assertThat(content.getValue().subject()).isEqualTo("SPOTIFY fell to CAD 11.99");
        assertThat(content.getValue().body()).contains("CAD 3.00 less");
    }

    @Test
    @DisplayName("a member who turned subscription alerts off is not told")
    void subscriptionAlertsRespectThePreference() {
        UUID owner = member("America/Toronto", null, true);
        householdHas(owner);
        rows.get(owner).setSubscriptionAlertEnabled(false);

        listener.onSubscriptionPriceChanged(new SubscriptionPriceChangedEvent(HOUSEHOLD, owner,
            SUBSCRIPTION, "NETFLIX", Money.of("18.99", CurrencyCode.CAD),
            Money.of("22.99", CurrencyCode.CAD)));

        verify(notifications, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("every active member with the toggle on gets the price-change alert, once (CI-46)")
    void aPriceChangeFansOutToEveryActiveMemberWithTheToggleOn() {
        UUID admin = member("America/Toronto", null, true);
        UUID partner = member("America/Vancouver", null, true);
        UUID optedOut = member("America/Toronto", null, true);
        UUID dormant = member("America/Toronto", null, false);
        rows.get(optedOut).setSubscriptionAlertEnabled(false);
        householdHas(admin, partner, optedOut, dormant);

        listener.onSubscriptionPriceChanged(new SubscriptionPriceChangedEvent(HOUSEHOLD, admin,
            SUBSCRIPTION, "NETFLIX", Money.of("18.99", CurrencyCode.CAD),
            Money.of("22.99", CurrencyCode.CAD)));

        verify(notifications, times(1)).notify(eq(admin),
            eq(NotificationType.SUBSCRIPTION_PRICE_CHANGE), any());
        verify(notifications, times(1)).notify(eq(partner),
            eq(NotificationType.SUBSCRIPTION_PRICE_CHANGE), any());
        verify(notifications, never()).notify(eq(optedOut), any(), any());
        verify(notifications, never()).notify(eq(dormant), any(), any());
    }

    // ------------------------------------------------------------------ sync

    @Test
    @DisplayName("SYNC_FAILED is keyed by account and the RECIPIENT'S own local date")
    void theSyncKeyUsesTheRecipientsOwnDate() {
        // 02:00 UTC on 7 September is still the 6th in Vancouver. Keying on the container's date
        // would give this member two alerts on one of their evenings and none on another.
        UUID vancouver = member("America/Vancouver", null, true);
        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, vancouver, ACCOUNT,
            UUID.randomUUID(), SyncCompletedEvent.Outcome.FAILURE, 0, 0,
            "The bank refused the stored credentials."));

        verify(notifications).notify(eq(vancouver), eq(NotificationType.SYNC_FAILED),
            content.capture());
        assertThat(content.getValue().dedupKey()).isEqualTo(ACCOUNT + ":2026-09-06");
        assertThat(content.getValue().body())
            .contains("Nothing was imported.")
            .contains("The bank refused the stored credentials.");
    }

    @Test
    @DisplayName("a Toronto member gets a different key from the same UTC instant")
    void twoZonesGiveTwoKeys() {
        UUID toronto = member("America/Toronto", null, true);
        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, toronto, ACCOUNT,
            UUID.randomUUID(), SyncCompletedEvent.Outcome.FAILURE, 0, 0, "expired"));

        verify(notifications).notify(eq(toronto), any(), content.capture());
        assertThat(content.getValue().dedupKey()).isEqualTo(ACCOUNT + ":2026-09-06");
    }

    @Test
    @DisplayName("a PARTIAL_FAILURE says some rows landed; a SUCCESS notifies nobody")
    void onlyFailuresAreNotified() {
        UUID owner = member("America/Toronto", null, true);
        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, owner, ACCOUNT,
            UUID.randomUUID(), SyncCompletedEvent.Outcome.PARTIAL_FAILURE, 12, 3,
            "Three rows had no amount."));
        verify(notifications).notify(eq(owner), eq(NotificationType.SYNC_FAILED),
            content.capture());
        assertThat(content.getValue().body()).startsWith("Some transactions were imported");

        // The decision recorded in this step: a successful sync writes no inbox row. An inbox
        // that says "nothing happened" several times a day is one people stop opening.
        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, owner, ACCOUNT,
            UUID.randomUUID(), SyncCompletedEvent.Outcome.SUCCESS, 12, 0, null));
        verify(notifications, org.mockito.Mockito.times(1)).notify(any(), any(), any());
    }

    @Test
    @DisplayName("a failure with no recorded reason still says something an operator can act on")
    void aMissingReasonIsStillARealBody() {
        UUID owner = member("America/Toronto", null, true);
        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, owner, ACCOUNT,
            UUID.randomUUID(), SyncCompletedEvent.Outcome.FAILURE, 0, 0, null));

        verify(notifications).notify(eq(owner), any(), content.capture());
        assertThat(content.getValue().body()).contains("No reason was recorded.");
    }

    @Test
    @DisplayName("a member who turned sync alerts off is not told")
    void syncAlertsRespectThePreference() {
        UUID owner = member("America/Toronto", null, true);
        rows.get(owner).setSyncFailureAlertEnabled(false);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, owner, ACCOUNT,
            UUID.randomUUID(), SyncCompletedEvent.Outcome.FAILURE, 0, 0, "expired"));

        verify(notifications, never()).notify(any(), any(), any());
    }

    // ------------------------------------------------- C-01r: scheduled sync, no nagging

    @Test
    @DisplayName("C-01r: a scheduled failure on a healthy account is keyed by the run, not the day")
    void aScheduledFailureOnAHealthyAccountIsKeyedByTheRun() {
        UUID owner = member("America/Toronto", null, true);
        UUID run = UUID.randomUUID();
        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, owner, ACCOUNT, run,
            SyncCompletedEvent.Outcome.FAILURE, 0, 0, "expired",
            SyncCompletedEvent.FailureAlert.ONCE));

        verify(notifications).notify(eq(owner), eq(NotificationType.SYNC_FAILED),
            content.capture());
        assertThat(content.getValue().dedupKey())
            .as("the day key would swallow the alert for an account that recovers and fails "
                + "again on the same day")
            .isEqualTo(ACCOUNT + ":" + run);
    }

    @Test
    @DisplayName("C-01r: a scheduled failure on an account already failing tells nobody again")
    void aScheduledFailureOnAFailingAccountIsSilent() {
        UUID owner = member("America/Toronto", null, true);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, owner, ACCOUNT,
            UUID.randomUUID(), SyncCompletedEvent.Outcome.FAILURE, 0, 0, "expired",
            SyncCompletedEvent.FailureAlert.NONE));

        verify(notifications, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("C-01r: a scheduled run still honours the member's sync-alert preference")
    void aScheduledFailureHonoursThePreference() {
        UUID owner = member("America/Toronto", null, true);
        rows.get(owner).setSyncFailureAlertEnabled(false);

        listener.onSyncCompleted(new SyncCompletedEvent(HOUSEHOLD, owner, ACCOUNT,
            UUID.randomUUID(), SyncCompletedEvent.Outcome.FAILURE, 0, 0, "expired",
            SyncCompletedEvent.FailureAlert.ONCE));

        verify(notifications, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("C-01r: the old eight-argument event is a manual run, alerted daily as before")
    void theEightArgumentEventIsManual() {
        assertThat(new SyncCompletedEvent(HOUSEHOLD, UUID.randomUUID(), ACCOUNT,
            UUID.randomUUID(), SyncCompletedEvent.Outcome.FAILURE, 0, 0, "expired")
            .failureAlert()).isEqualTo(SyncCompletedEvent.FailureAlert.DAILY);
    }

    // ------------------------------------------------- collapse step 18: receipt.processed

    @Test
    @DisplayName("A receipt needing review notifies the uploader alone, keyed by the receipt id")
    void aReceiptNeedingReviewNotifiesTheUploader() {
        UUID uploader = member("America/Toronto", null, true);
        UUID receiptId = UUID.randomUUID();

        listener.onReceiptProcessed(new ReceiptProcessedEvent(HOUSEHOLD, uploader, receiptId,
            ReceiptProcessedEvent.Outcome.REVIEW_REQUIRED, null));

        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);
        verify(notifications).notify(eq(uploader), eq(NotificationType.RECEIPT_PROCESSED),
            content.capture());
        assertThat(content.getValue().dedupKey())
            .as("a receipt is processed once, and a re-run of the pipeline over the same image is "
                + "the same fact about the same photograph")
            .isEqualTo(receiptId.toString());
        assertThat(content.getValue().subject())
            .as("REVIEW_REQUIRED is the ORDINARY outcome on an install with no vision model; "
                + "wording it as a failure would stop members uploading")
            .isEqualTo("A receipt needs your review");
        assertThat(content.getValue().actionUrl()).isEqualTo("/receipts/" + receiptId);
    }

    @Test
    @DisplayName("A failed receipt is worded as a failure and still goes only to the uploader")
    void aFailedReceiptIsWordedAsOne() {
        UUID uploader = member("America/Toronto", null, true);
        UUID receiptId = UUID.randomUUID();

        listener.onReceiptProcessed(new ReceiptProcessedEvent(HOUSEHOLD, uploader, receiptId,
            ReceiptProcessedEvent.Outcome.FAILED, null));

        ArgumentCaptor<NotificationContent> content =
            ArgumentCaptor.forClass(NotificationContent.class);
        verify(notifications).notify(eq(uploader), eq(NotificationType.RECEIPT_PROCESSED),
            content.capture());
        assertThat(content.getValue().subject()).isEqualTo("A receipt could not be processed");
    }

    @Test
    @DisplayName("A receipt that processed cleanly puts nothing in anybody's inbox")
    void aCleanReceiptNotifiesNobody() {
        UUID uploader = member("America/Toronto", null, true);

        listener.onReceiptProcessed(new ReceiptProcessedEvent(HOUSEHOLD, uploader,
            UUID.randomUUID(), ReceiptProcessedEvent.Outcome.COMPLETE, UUID.randomUUID()));

        verify(notifications, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("A receipt uploaded by a member who has since been deleted notifies nobody")
    void aDormantUploaderIsNotNotified() {
        // Collapse step 18. A pipeline run can finish AFTER its uploader deleted their account —
        // the orphan sweep re-runs stuck receipts hours later — and the soft delete leaves the
        // user row in place for the restore grace. Without the liveness check an inbox row lands
        // in an account that has been deleted, which DELETE /v1/me's contract says will not
        // happen.
        UUID gone = member("America/Toronto", null, false);

        listener.onReceiptProcessed(new ReceiptProcessedEvent(HOUSEHOLD, gone, UUID.randomUUID(),
            ReceiptProcessedEvent.Outcome.REVIEW_REQUIRED, null));

        verify(notifications, never()).notify(any(), any(), any());
    }

    @Test
    @DisplayName("onReceiptProcessed is AFTER_COMMIT + REQUIRES_NEW with fallbackExecution = true")
    void theReceiptHandlerCarriesTheFallback() throws Exception {
        // ReceiptEventPublisher.publishAfterCommit branches on isSynchronizationActive(), and the
        // ordinary case — ReceiptPipelineOrchestrator, which calls it outside any transaction
        // after its final save has committed — takes the "publish immediately" branch. With the
        // default fallbackExecution = false Spring drops that event SILENTLY, and this handler is
        // dead code: a member whose receipt needs review would never be told.
        java.lang.reflect.Method handler = NotificationEventListener.class
            .getMethod("onReceiptProcessed", ReceiptProcessedEvent.class);

        TransactionalEventListener annotation =
            handler.getAnnotation(TransactionalEventListener.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution())
            .as("without this the inbox consumer of receipt.processed never runs at all")
            .isTrue();

        TransactionAttribute attribute = new AnnotationTransactionAttributeSource()
            .getTransactionAttribute(handler, NotificationEventListener.class);
        assertThat(attribute).isNotNull();
        assertThat(attribute.getPropagationBehavior())
            .as("after the commit there is no transaction left to join")
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
