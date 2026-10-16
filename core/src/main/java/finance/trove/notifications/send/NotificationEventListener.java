package finance.trove.notifications.send;

import finance.trove.common.domain.Money;
import finance.trove.common.events.BudgetThresholdCrossedEvent;
import finance.trove.common.events.ReceiptProcessedEvent;
import finance.trove.common.events.SubscriptionPriceChangedEvent;
import finance.trove.common.events.SyncCompletedEvent;
import finance.trove.common.time.UserClock;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.preference.NotificationPreference;
import finance.trove.notifications.preference.NotificationPreferenceService;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The four domain events that produce a notification
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D3).
 *
 * <h2>Why every handler is AFTER_COMMIT plus REQUIRES_NEW</h2>
 * {@code AFTER_COMMIT} because a notification about a transaction that rolled back is a lie: the
 * envelope did not really cross, the price did not really change, and the member cannot act on
 * either. {@code REQUIRES_NEW} because after the commit there is no transaction left to join —
 * a plain {@code @Transactional} would silently not open one and every write here would fail or,
 * worse, run outside a transaction one statement at a time.
 *
 * <h2>Who is told, and why it differs between the producers</h2>
 * <ul>
 *   <li><strong>Budget threshold — every member whose own threshold is crossed.</strong> A budget
 *       is the household's shared plan and every member spends against it, so a crossing concerns
 *       all of them; and members hold their own thresholds in {@code notification_preference}, so
 *       "crossed" is a different question for each one.
 *       {@code EnvelopeSpendService} publishes on the crossing of ANY member's ratio and this
 *       handler decides which members that ratio actually reached. V32's per-(user, type, key)
 *       unique index makes the second half idempotent: a member is told once per envelope per
 *       month however many crossings are published.</li>
 *   <li><strong>Price change — every active member.</strong> A detected subscription belongs to
 *       the household, not to the member the scan happened to be attributed to, and each member
 *       holds their own subscription-alert toggle, so the handler fans out the way the budget one
 *       does. V32's per-(user, type, key) unique index keeps it to one alert per member per new
 *       price.</li>
 *   <li><strong>Sync failure — the member the event names.</strong> A connection is owned by
 *       whoever added it. Fanning that out would put one member's preference in charge of
 *       another member's notification and would put N copies of "your bank connection failed"
 *       into a household that has one bank connection.</li>
 * </ul>
 *
 * <p><strong>A SUCCESSFUL sync notifies nobody</strong>, and that is a decision rather than an
 * omission. {@code SyncCompletedEvent} is published on success as well as failure, and §9 risk 13
 * asserts the failure path only. An inbox row per successful sync would be one a day
 * per account saying nothing happened, which is how a notification centre becomes something people
 * stop opening — and the one thing this step exists to make worth opening is the inbox.
 *
 * <p><strong>Collapse step 18 adds the fourth producer, {@code ReceiptProcessedEvent}</strong>
 * ({@link #onReceiptProcessed}), which step 16 specified and step 14 could not land: step 14
 * shipped the publisher and no consumer. V44 widens the CHECK, {@code NotificationType} gains the
 * constant and the handler below arrives with them, because none of the three is valid alone.
 */
@Component
public class NotificationEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationEventListener.class);

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final NotificationService notifications;
    private final NotificationPreferenceService preferences;
    private final UserRepository users;
    private final UserClock clock;

    public NotificationEventListener(NotificationService notifications,
                                     NotificationPreferenceService preferences,
                                     UserRepository users, UserClock clock) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.users = users;
        this.clock = clock;
    }

    /**
     * An envelope crossed one of the household's alert thresholds.
     *
     * <p>Dedup key: {@code envelopeId:month}. One alert per envelope, per member, per budget
     * period — which is what {@code BudgetThresholdCrossedEvent}'s own javadoc means by "published
     * on the crossing, not on every recalculation", now enforced by the database rather than by
     * the publisher's care.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBudgetThresholdCrossed(BudgetThresholdCrossedEvent event) {
        String dedupKey = event.envelopeId() + ":" + event.month();
        List<User> members = users.findAllByHouseholdIdOrderByCreatedAtAsc(event.householdId());
        for (User member : members) {
            if (!member.isActive()) {
                continue;
            }
            NotificationPreference preference = preferences.getOrCreate(member.getId());
            if (!reached(event.spent(), event.allocated(),
                preference.getBudgetAlertThresholdPercent())) {
                continue;
            }
            notifications.notify(member.getId(), NotificationType.BUDGET_THRESHOLD,
                NotificationContent.of(budgetSubject(event), budgetBody(event),
                    "/budgets/" + event.month(), dedupKey));
        }
    }

    /**
     * A detected recurring charge changed price. Every active member of the household whose own
     * subscription-alert toggle is on is told: the subscription belongs to the household, and the
     * toggle is offered to every member, so the event's {@code userId} is not read here: only
     * {@code householdId} decides who hears.
     *
     * <p>Dedup key: {@code subscriptionId:newAmount}. A price that changes twice is two alerts; a
     * rescan that sees the same new price is one. Keying on the new amount rather than on a date
     * is what makes that true without a timer — the second scan produces the identical key and its
     * INSERT conflicts.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSubscriptionPriceChanged(SubscriptionPriceChangedEvent event) {
        Money delta = event.delta();
        String direction = delta.isNegative() ? "fell" : "rose";
        String subject = event.merchantName() + " " + direction + " to "
            + money(event.newAmount());
        String body = event.merchantName() + " changed from " + money(event.previousAmount())
            + " to " + money(event.newAmount()) + " (" + money(delta.abs()) + " "
            + (delta.isNegative() ? "less" : "more") + ").";
        NotificationContent content = NotificationContent.of(subject, body,
            "/subscriptions/" + event.subscriptionId(),
            event.subscriptionId() + ":" + event.newAmount().amount().toPlainString());
        for (User member : users.findAllByHouseholdIdOrderByCreatedAtAsc(event.householdId())) {
            if (member.isActive()
                && preferences.getOrCreate(member.getId()).isSubscriptionAlertEnabled()) {
                notifications.notify(member.getId(), NotificationType.SUBSCRIPTION_PRICE_CHANGE,
                    content);
            }
        }
    }

    /**
     * A sync failed, wholly or in part.
     *
     * <p>Dedup key: {@code accountId:the recipient's own local date}. A connection whose
     * credentials expired fails on every scheduled run (daily by default); the member needs to be told, once, on the day
     * it happens. The date is the RECIPIENT'S, from {@link UserClock}, not the container's — a
     * Vancouver member's day starts 7 to 8 hours after UTC's, and using the container's would
     * put two alerts in their inbox on one of their evenings and none on another.
     *
     * <p>That day key is for a MANUAL run ({@code FailureAlert.DAILY}). A scheduled run
     * (C-01r, {@code ScheduledSyncJob}) would turn it into a daily nag for a connection nobody has
     * fixed, so the job says before each run whether the account was healthy: {@code ONCE} keys
     * the alert by the run itself — the move from healthy to failing is always news, even on a day
     * that already had one — and {@code NONE} stays silent, because the member has been told.
     *
     * <h2>Why onSyncCompleted carries {@code fallbackExecution = true} and the budget and price handlers do not</h2>
     * The default, {@code fallbackExecution = false}, means "if no transaction is active when the
     * event is published, drop it" — and Spring drops it silently, with no log and no error.
     * {@code SyncOrchestrator} is <strong>deliberately not</strong> {@code @Transactional} (its own
     * class javadoc says so, and the reason is that a sync must not hold one connection across a
     * bank call): every one of its short transactions — {@code SyncStateWriter.claim},
     * {@code start}, {@code release} and {@code finish} (all {@code REQUIRES_NEW}) and
     * {@code IngestionBatchWriter.persist} — has already
     * committed and returned by the time it publishes. So at the publish point there is no
     * transaction synchronization active, and without this flag the handler is never invoked at
     * all: {@code SYNC_FAILED} would be dead code, a member whose bank credentials expired would
     * never be told, and no {@code notification_log} or {@code in_app_notification} row would ever
     * be written for it. That is exactly the class of silent no-op §9 risk 13 exists to catch, and
     * {@code SyncFailureNotificationIT} drives a real failing sync end to end so that it stays
     * caught.
     *
     * <p>Running inline on the publisher's thread is safe HERE precisely because of the same fact:
     * the sync's own writes are already committed, so there is nothing left to roll back and
     * nothing this handler can see half-done. Its own writes are {@code REQUIRES_NEW}, so they get
     * a transaction of their own either way. The budget-threshold and price-change handlers keep the default because both
     * of their publishers ({@code EnvelopeSpendService.recalculateAll} and
     * {@code SubscriptionDetectionService.detectForHousehold}, both {@code @Transactional}) publish
     * INSIDE a live transaction, where AFTER_COMMIT is the guarantee that matters and a fallback
     * would only mask a future publisher that lost its transaction.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSyncCompleted(SyncCompletedEvent event) {
        if (event.isSuccess() || event.failureAlert() == SyncCompletedEvent.FailureAlert.NONE) {
            return;
        }
        NotificationPreference preference = preferences.getOrCreate(event.userId());
        if (!preference.isSyncFailureAlertEnabled()) {
            return;
        }
        boolean partial = event.outcome() == SyncCompletedEvent.Outcome.PARTIAL_FAILURE;
        String subject = partial
            ? "An account sync finished with problems"
            : "An account could not be synced";
        String reason = event.failureReason() == null || event.failureReason().isBlank()
            ? "No reason was recorded." : event.failureReason();
        String body = (partial
            ? "Some transactions were imported and some were rejected. "
            : "Nothing was imported. ") + reason;
        String dedupKey = event.accountId() + ":"
            + (event.failureAlert() == SyncCompletedEvent.FailureAlert.ONCE
                ? event.syncLogId() : clock.today(event.userId()));
        notifications.notify(event.userId(), NotificationType.SYNC_FAILED,
            NotificationContent.of(subject, body, "/accounts/" + event.accountId(), dedupKey));
        LOG.debug("sync failure notification queued for account {}", event.accountId());
    }

    /**
     * A receipt finished its pipeline (collapse step 18; ⚙ 28, specified by step 16).
     *
     * <p>Recipient: {@code event.userId()}, the UPLOADER, and nobody else. A receipt is a shared
     * household ledger row, but "the photograph you took could not be read" is one person's
     * problem and fanning it out would put N copies of it in a household with one camera.
     *
     * <p>Dedup key: the receipt id ALONE. A receipt is processed once, and a re-run of the
     * pipeline over the same image is the same fact about the same photograph — V32's
     * per-(user, type, key) unique index makes the second attempt a no-op rather than a second
     * inbox row.
     *
     * <p><strong>A clean outcome notifies nobody.</strong> {@code COMPLETE} means the fields were
     * extracted and a transaction was matched: there is nothing for the member to do, and step
     * 11's standing rule is that a silent success earns no inbox row — the same reason a
     * successful sync produces none. Only {@code REVIEW_REQUIRED} and {@code FAILED} reach the
     * inbox. {@code REVIEW_REQUIRED} is worded as work, not as an error: it is the ORDINARY
     * outcome on an install with no vision model, exactly as {@code ReceiptProcessedEvent}'s own
     * javadoc says, and a member told their upload "failed" when it is simply waiting for them
     * would reasonably stop uploading.
     *
     * <h2>Why this one carries {@code fallbackExecution = true}</h2>
     * The same reason {@link #onSyncCompleted} does, and it is not defensive: it is the only
     * thing that makes this handler run at all. {@code ReceiptEventPublisher.publishAfterCommit}
     * branches on {@code TransactionSynchronizationManager.isSynchronizationActive()} — with no
     * synchronization active it publishes IMMEDIATELY, and that is the ordinary case, because
     * {@code ReceiptPipelineOrchestrator} calls it outside any transaction after its final save
     * has already committed. With the default {@code fallbackExecution = false} Spring drops an
     * event published outside a transaction SILENTLY, with no log and no error, and this handler
     * would be dead code: a member whose receipt needs review would never be told and no
     * {@code in_app_notification} row would ever exist for it. That is exactly the class of
     * silent no-op step 11's review found in {@code SYNC_FAILED}, and {@code ReceiptSeamIT} fails
     * if the flag is removed.
     *
     * <p>Running inline on the publisher's thread is safe here for the same reason it is there:
     * the pipeline's writes are already committed, so there is nothing left to roll back and
     * nothing this handler can see half-done. Its own writes are {@code REQUIRES_NEW}, so they
     * get a transaction of their own either way.
     *
     * <h2>The uploader has to still be there</h2>
     * This is the one producer on this bean that targets a named member and is published from a
     * background pipeline that can finish AFTER that member left. A receipt takes seconds to
     * process and the orphan sweep re-runs stuck ones hours later, so an upload can land in the
     * inbox of an account whose owner deleted it in between — and {@code DELETE /v1/me}'s own
     * contract promises the opposite in as many words. Since collapse step 18 a deleted member's
     * row survives the restore grace with {@code is_active = false}, so the flag every other
     * check in the tree already reads is the one this reads: the recipient is resolved inside the
     * household the event names and a dormant one is skipped in silence. The household fan-out
     * above has always done this ({@code member.isActive()}); this is the same rule applied to a
     * per-member producer.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReceiptProcessed(ReceiptProcessedEvent event) {
        if (event.outcome() == ReceiptProcessedEvent.Outcome.COMPLETE) {
            return;
        }
        if (!isLive(event.userId(), event.householdId())) {
            return;
        }
        boolean failed = event.outcome() == ReceiptProcessedEvent.Outcome.FAILED;
        String subject = failed
            ? "A receipt could not be processed"
            : "A receipt needs your review";
        String body = failed
            ? "The image was stored but could not be read. Open it to enter the details by hand, "
                + "or upload a clearer photograph."
            : "The receipt was stored and attached. Check the merchant, the amount and the "
                + "matched transaction, and correct anything that is wrong.";
        notifications.notify(event.userId(), NotificationType.RECEIPT_PROCESSED,
            NotificationContent.of(subject, body, "/receipts/" + event.receiptId(),
                event.receiptId().toString()));
        LOG.debug("receipt notification queued for receipt {} ({})", event.receiptId(),
            event.outcome());
    }

    /**
     * Whether {@code userId} is still a live member of {@code householdId}.
     *
     * <p>Scoped by the household the event carries rather than looked up unscoped: the two are
     * always the pair the publisher had in hand, and a scoped read is the tree's rule even where
     * the id came from a trusted event.
     */
    private boolean isLive(UUID userId, UUID householdId) {
        return users.findByIdAndHouseholdId(userId, householdId)
            .filter(User::isActive)
            .isPresent();
    }

    /** Whether {@code spent} has reached {@code percent} of {@code allocated}. */
    private static boolean reached(Money spent, Money allocated, short percent) {
        if (allocated.amount().signum() <= 0) {
            return false;
        }
        BigDecimal trigger = allocated.amount()
            .multiply(BigDecimal.valueOf(percent))
            .divide(ONE_HUNDRED, allocated.currency().minorUnits(), RoundingMode.HALF_UP);
        return spent.amount().compareTo(trigger) >= 0;
    }

    private static String budgetSubject(BudgetThresholdCrossedEvent event) {
        int percent = percentOf(event.spent(), event.allocated());
        return event.isOverspent()
            ? "A budget is over by " + money(event.spent().subtract(event.allocated()))
            : "A budget is at " + percent + "% for " + event.month();
    }

    private static String budgetBody(BudgetThresholdCrossedEvent event) {
        return "You have spent " + money(event.spent()) + " of the " + money(event.allocated())
            + " budgeted for " + event.month() + ".";
    }

    private static int percentOf(Money spent, Money allocated) {
        return spent.amount().multiply(ONE_HUNDRED)
            .divide(allocated.amount(), 0, RoundingMode.DOWN).intValue();
    }

    /** {@code CAD 368.00} — the currency is never converted and never dropped. */
    private static String money(Money amount) {
        return amount.currency().name() + " " + amount.amount().toPlainString();
    }
}
