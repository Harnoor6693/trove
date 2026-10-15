package finance.trove.notifications.send;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.ingestion.TestBankSource;
import finance.trove.notifications.NotificationSupport;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A real failing sync, driven through {@code POST /v1/accounts/{id}/sync}, produces the
 * {@code SYNC_FAILED} notification — §9 risk 13's actual subject.
 *
 * <h2>Why this file exists, and why a unit test could not have caught what it catches</h2>
 * {@code NotificationEventListener.onSyncCompleted} is a {@code @TransactionalEventListener}, and
 * the default {@code fallbackExecution = false} means Spring DROPS the event, silently, when no
 * transaction is active at the publish point. {@code SyncOrchestrator} is deliberately not
 * {@code @Transactional} — its class javadoc says so — and it publishes after every one of its
 * short {@code REQUIRES_NEW} transactions has already committed and returned. So the handler was
 * never invoked in production at all: a member whose bank credentials had expired was never told,
 * and no {@code notification_log} or {@code in_app_notification} row was ever written for a sync
 * failure.
 *
 * <p>Nothing could see it. {@code NotificationEventListenerTest} calls
 * {@code listener.onSyncCompleted(...)} directly, which bypasses the publisher and therefore the
 * entire question; no other test drove a sync and looked at an inbox. The only shape of test that
 * can see a dropped event is this one — publish through the REAL producer, over HTTP, and assert
 * on the row that should exist. It is written for the other two listeners' benefit as much as for
 * this one: whatever a future step does to a publisher's transaction boundary, this fails.
 */
class SyncFailureNotificationIT extends NotificationSupport {

    @Autowired
    private TestBankSource source;

    private Session member;
    private String accountId;

    @BeforeEach
    void setUpAnAccount() {
        source.reset();
        member = inviteAndRegister(loginBootstrapAdmin(), "sync-notify");
        accountId = createSyncableAccount(member, "Sync " + UUID.randomUUID());
    }

    @AfterEach
    void releaseTheSource() {
        source.releaseFetch();
        source.reset();
    }

    private HttpResponse<String> sync() {
        return postJson(appUrl("/v1/accounts/" + accountId + "/sync"), null,
            "Authorization", "Bearer " + member.accessToken());
    }

    /** The inbox rows this account's failures would have written. */
    private List<JsonNode> syncFailures() {
        return inbox(member).stream()
            .filter(row -> "SYNC_FAILED".equals(row.path("type").asString()))
            .filter(row -> ("/accounts/" + accountId).equals(row.path("actionUrl").asString()))
            .toList();
    }

    private int logRows() {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_log WHERE user_id = ? AND type = 'SYNC_FAILED' "
                + "AND dedup_key LIKE ?", Integer.class, member.userId(), accountId + ":%");
    }

    @Test
    @DisplayName("A sync that cannot reach its source tells the member, through the real publisher")
    void aFailedSyncNotifiesTheMember() {
        source.failingWith("The saved credential was rejected by the institution.");

        HttpResponse<String> response = sync();

        assertThat(Json.data(response.body()).path("status").asString())
            .as("the sync itself reports the failure; the notification is the second half")
            .isEqualTo("FAILED");
        List<JsonNode> failures = syncFailures();
        assertThat(failures)
            .as("the event is published after every one of the orchestrator's transactions has "
                + "committed, so an AFTER_COMMIT listener without fallbackExecution never runs "
                + "and this list was empty — SYNC_FAILED was dead code in production")
            .hasSize(1);
        assertThat(failures.get(0).path("title").asString())
            .isEqualTo("An account could not be synced");
        assertThat(failures.get(0).path("body").asString())
            .as("the member is told WHY, in the words the source gave")
            .contains("The saved credential was rejected by the institution.");
        assertThat(logRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("A connection failing twice on one day is one alert, not one per attempt")
    void aRepeatedFailureIsOneAlertADay() {
        // The dedup key is accountId + the RECIPIENT'S own local date: a credential that expired
        // fails on every hourly run and the member needs telling once.
        source.failingWith("The saved credential was rejected by the institution.");

        sync();
        sync();

        assertThat(syncFailures()).hasSize(1);
        assertThat(logRows()).isEqualTo(1);
        // The date in the key is the RECIPIENT'S, from UserClock — not the container's. Read
        // their stored zone rather than assuming one, which is the same thing the listener does.
        String zone = jdbcTemplate.queryForObject(
            "SELECT timezone FROM users WHERE id = ?", String.class, member.userId());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT dedup_key FROM notification_log WHERE user_id = ? AND type = 'SYNC_FAILED' "
                + "AND dedup_key LIKE ?", String.class, member.userId(), accountId + ":%"))
            .as("the key carries its own window: the account and the recipient's local date")
            .isEqualTo(accountId + ":" + LocalDate.now(ZoneId.of(zone)));
    }

    @Test
    @DisplayName("A sync that worked writes nothing: a successful hourly run is not news")
    void aSuccessfulSyncNotifiesNobody() {
        source.returning(List.of());

        assertThat(Json.data(sync().body()).path("status").asString()).isEqualTo("SUCCESS");

        assertThat(syncFailures()).isEmpty();
        assertThat(logRows())
            .as("an inbox row per successful sync would be several a day per account saying "
                + "nothing happened, which is how a notification centre stops being opened")
            .isZero();
    }

    @Test
    @DisplayName("A member who switched sync alerts off is not told")
    void theSyncAlertPreferenceIsHonoured() {
        putJson(appUrl("/v1/notifications/preferences"),
            Json.object("syncFailureAlertEnabled", false), bearer(member));
        source.failingWith("The saved credential was rejected by the institution.");

        sync();

        assertThat(syncFailures()).isEmpty();
        assertThat(logRows()).isZero();
    }
}
