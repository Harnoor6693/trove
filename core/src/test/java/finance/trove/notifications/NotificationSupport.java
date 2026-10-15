package finance.trove.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.notifications.send.NotificationContent;
import finance.trove.notifications.send.NotificationService;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Shared setup for the notification integration tests.
 *
 * <p>Abstract, and without the {@code IT} suffix, so Failsafe does not try to run it.
 *
 * <p>Notifications are seeded through {@link NotificationService} rather than by driving a budget
 * over its threshold through the API. That is deliberate: the route tests are about the five inbox
 * operations, and building a realistic crossing for each of them would make every one of them also
 * a test of budgets. The event path is covered where it belongs, in
 * {@code NotificationDedupIT} and in {@code NotificationEventListenerTest}.
 */
public abstract class NotificationSupport extends LedgerSupport {

    @Autowired
    protected NotificationService notificationService;

    /** Writes one notification for {@code userId} and returns whether it was new. */
    protected boolean seed(UUID userId, NotificationType type, String subject, String dedupKey) {
        return notificationService.notify(userId, type,
            NotificationContent.of(subject, subject + " body", "/dashboard", dedupKey));
    }

    /** Writes one notification with a key nothing else can collide with. */
    protected void seedUnique(UUID userId, NotificationType type, String subject) {
        assertThat(seed(userId, type, subject, UUID.randomUUID().toString()))
            .as("a fresh dedup key must always claim")
            .isTrue();
    }

    /** One page of the caller's inbox, as the API returns it. */
    protected List<JsonNode> inbox(Session session, String query) {
        HttpResponse<String> response = get(
            appUrl("/v1/notifications" + (query.isEmpty() ? "" : "?" + query)),
            session.accessToken());
        assertThat(response.statusCode())
            .as("listing notifications failed: %s", response.body())
            .isEqualTo(200);
        return Json.elements(Json.data(response.body()));
    }

    /** The inbox with the default paging. */
    protected List<JsonNode> inbox(Session session) {
        return inbox(session, "");
    }

    /** The ids in the caller's inbox, newest first. */
    protected List<String> inboxIds(Session session) {
        return inbox(session).stream().map(row -> row.path("id").asString()).toList();
    }

    /** The badge number the mobile client polls. */
    protected long unreadCount(Session session) {
        HttpResponse<String> response =
            get(appUrl("/v1/notifications/unread-count"), session.accessToken());
        assertThat(response.statusCode())
            .as("reading the unread count failed: %s", response.body())
            .isEqualTo(200);
        return Json.data(response.body()).path("unread").asLong();
    }

    /** The row with this id from the caller's inbox, or null when it is not listed. */
    protected JsonNode inboxRow(Session session, String id) {
        return inbox(session).stream()
            .filter(row -> id.equals(row.path("id").asString()))
            .findFirst().orElse(null);
    }

    /** Bearer-mode headers for a mutation. */
    protected String[] bearer(Session session) {
        return new String[] {"Authorization", "Bearer " + session.accessToken()};
    }
}
