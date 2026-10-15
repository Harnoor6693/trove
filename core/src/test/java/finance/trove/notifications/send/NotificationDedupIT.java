package finance.trove.notifications.send;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import finance.trove.common.events.BudgetThresholdCrossedEvent;
import finance.trove.notifications.NotificationSupport;
import finance.trove.notifications.NotificationType;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deduplication, against the real partial unique index (§6 D3, V32).
 *
 * <p><strong>The key is the mechanism, not a timer.</strong> Every type states its own key with
 * its window encoded in it, and {@code notification_log_dedup_idx} is what makes two producers
 * that really can run at once unable to both send. The alternative shape — "have we sent one of
 * these in the last N hours?" — is a check-then-act across two transactions, and the race below is
 * exactly the interleaving it loses to: both readers see "no", both write.
 */
class NotificationDedupIT extends NotificationSupport {

    private static final UUID ENVELOPE = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Session member;

    @BeforeEach
    void signIn() {
        member = inviteAndRegister(loginBootstrapAdmin(), "dedup-member");
    }

    private long logRows(String dedupKey) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_log WHERE user_id = ? AND dedup_key = ?",
            Long.class, member.userId(), dedupKey);
    }

    private long inboxRows(String title) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM in_app_notification WHERE user_id = ? AND title = ?",
            Long.class, member.userId(), title);
    }

    @Test
    @DisplayName("The same notification twice is one log row and one inbox row")
    void theSameKeyIsSentOnce() {
        String key = ENVELOPE + ":2026-09";

        assertThat(seed(member.userId(), NotificationType.BUDGET_THRESHOLD, "Groceries", key))
            .isTrue();
        assertThat(seed(member.userId(), NotificationType.BUDGET_THRESHOLD, "Groceries", key))
            .as("the second call finds the claim already taken and does nothing else")
            .isFalse();

        assertThat(logRows(key)).isEqualTo(1);
        assertThat(inboxRows("Groceries"))
            .as("no second inbox row either: the claim comes BEFORE the inbox write for exactly "
                + "this reason")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("A different month is a different key, and a second notification")
    void adifferentWindowIsADifferentNotification() {
        seed(member.userId(), NotificationType.BUDGET_THRESHOLD, "September",
            ENVELOPE + ":2026-09");

        assertThat(seed(member.userId(), NotificationType.BUDGET_THRESHOLD, "October",
            ENVELOPE + ":2026-10"))
            .as("the window is encoded IN the key, so next month alerts again with no timer "
                + "anywhere")
            .isTrue();

        assertThat(logRows(ENVELOPE + ":2026-09")).isEqualTo(1);
        assertThat(logRows(ENVELOPE + ":2026-10")).isEqualTo(1);
    }

    @Test
    @DisplayName("A different TYPE with the same key is a different notification")
    void theTypeIsPartOfTheKey() {
        // The index is over (user_id, type, dedup_key). Two types that happened to build the same
        // key string must not silence each other.
        String key = UUID.randomUUID().toString();

        assertThat(seed(member.userId(), NotificationType.SYNC_FAILED, "Sync", key)).isTrue();
        assertThat(seed(member.userId(), NotificationType.WEEKLY_DIGEST, "Digest", key)).isTrue();

        assertThat(logRows(key)).isEqualTo(2);
    }

    @Test
    @DisplayName("A null key is never deduplicated — that is what null means here")
    void nullKeysAreNotDeduplicated() {
        // V32's index is PARTIAL for this reason. NULLS NOT DISTINCT would collapse every
        // undeduplicated notification of one type into a single row per member.
        String title = "One-off " + UUID.randomUUID();

        assertThat(notificationService.notify(member.userId(), NotificationType.SYNC_FAILED,
            NotificationContent.of(title, "body", null))).isTrue();
        assertThat(notificationService.notify(member.userId(), NotificationType.SYNC_FAILED,
            NotificationContent.of(title, "body", null))).isTrue();

        assertThat(inboxRows(title)).isEqualTo(2);
    }

    @Test
    @DisplayName("A different member with the same key gets their own notification")
    void theKeyIsScopedToTheMember() {
        Session mate = inviteAndRegister(loginBootstrapAdmin(), "dedup-mate");
        String key = ENVELOPE + ":2026-11";

        assertThat(seed(member.userId(), NotificationType.BUDGET_THRESHOLD, "Shared", key))
            .isTrue();
        assertThat(seed(mate.userId(), NotificationType.BUDGET_THRESHOLD, "Shared", key))
            .as("a household budget crossing concerns both members; the index is per member")
            .isTrue();
    }

    @Test
    @DisplayName("Two threads racing the same key produce exactly one notification")
    void twoThreadsRacingTheSameKeyProduceOne() throws Exception {
        // A real two-thread race, the way InviteIT's redemption and AccessControlIT's last-admin
        // guard are. Postgres decides the winner on the unique index, so a flake here would show
        // up as TWO notifications, never as a false pass — which is why the assertion is on the
        // row count rather than on which thread won.
        String key = ENVELOPE + ":race-" + UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> attempt = () -> {
            start.await(5, TimeUnit.SECONDS);
            return seed(member.userId(), NotificationType.BUDGET_THRESHOLD, "Raced", key);
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(attempt);
            Future<Boolean> second = pool.submit(attempt);
            start.countDown();
            List<Boolean> outcomes = List.of(first.get(30, TimeUnit.SECONDS),
                second.get(30, TimeUnit.SECONDS));

            assertThat(outcomes)
                .as("exactly one thread takes the claim; the other's INSERT conflicts and it "
                    + "returns having done nothing")
                .containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        assertThat(logRows(key)).isEqualTo(1);
        assertThat(inboxRows("Raced")).isEqualTo(1);
    }

    @Test
    @DisplayName("Two crossings of one envelope in one month reach the member once")
    void twoCrossingsAreOneAlert() {
        // The end-to-end shape of the step-8 swap. EnvelopeSpendService publishes on the crossing
        // of ANY member's threshold, so one envelope can produce several events in a month; the
        // dedup key is what makes that safe.
        UUID envelope = UUID.randomUUID();
        publishCrossing(envelope, "72.00", "100.00");
        publishCrossing(envelope, "96.00", "100.00");

        assertThat(logRows(envelope + ":2026-09"))
            .as("one alert per envelope, per member, per budget period")
            .isEqualTo(1);
        assertThat(inbox(member))
            .filteredOn(row -> "BUDGET_THRESHOLD".equals(row.path("type").asString()))
            .hasSize(1);
    }

    /**
     * Publishes a crossing inside a transaction and commits it, so that the
     * {@code AFTER_COMMIT} listener actually runs. A bare {@code publishEvent} outside a
     * transaction would be delivered to nothing.
     */
    private void publishCrossing(UUID envelopeId, String spent, String allocated) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
            status -> events.publishEvent(
                new BudgetThresholdCrossedEvent(member.householdId(), member.userId(),
                    envelopeId, CATEGORY, YearMonth.of(2026, 9),
                    Money.of(spent, CurrencyCode.CAD), Money.of(allocated, CurrencyCode.CAD))));
    }
}
