package finance.trove.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The five inbox routes end to end (§4.9).
 *
 * <p>The route that matters most here is the {@code DELETE}: it is a DISMISSAL, and what proves it
 * is not a delete is that the row stops being listed while the count of what the member was told
 * does not go backwards. The pre-collapse service had all five of these endpoints over a table
 * that nothing ever wrote to (finding D3), so every one of them answered an empty list on every
 * install; they are exercised here against rows a real producer wrote.
 */
class NotificationIT extends NotificationSupport {

    private Session member;

    @BeforeEach
    void signIn() {
        member = inviteAndRegister(loginBootstrapAdmin(), "inbox-member");
    }

    @Test
    @DisplayName("A new notification is listed, unread, and counted")
    void aNewNotificationIsListedAndCounted() {
        assertThat(inbox(member)).isEmpty();
        assertThat(unreadCount(member)).isZero();

        seedUnique(member.userId(), NotificationType.BUDGET_THRESHOLD, "Groceries is at 92%");

        List<JsonNode> rows = inbox(member);
        assertThat(rows).hasSize(1);
        JsonNode row = rows.getFirst();
        assertThat(row.path("type").asString()).isEqualTo("BUDGET_THRESHOLD");
        assertThat(row.path("title").asString()).isEqualTo("Groceries is at 92%");
        assertThat(row.path("body").asString()).isEqualTo("Groceries is at 92% body");
        assertThat(row.path("actionUrl").asString()).isEqualTo("/dashboard");
        assertThat(row.path("read").asBoolean()).isFalse();
        assertThat(row.path("dismissed").asBoolean()).isFalse();
        assertThat(row.path("readAt").isNull()).isTrue();
        assertThat(row.path("dismissedAt").isNull()).isTrue();
        assertThat(row.path("createdAt").asString()).isNotBlank();
        assertThat(unreadCount(member)).isEqualTo(1);
    }

    @Test
    @DisplayName("The list is newest first and pages with the shared page/limit parameters")
    void theListIsPagedNewestFirst() {
        for (int index = 0; index < 3; index++) {
            seedUnique(member.userId(), NotificationType.SYNC_FAILED, "Sync failure " + index);
        }

        HttpResponse<String> firstPage =
            get(appUrl("/v1/notifications?page=0&limit=2"), member.accessToken());
        assertThat(firstPage.statusCode()).isEqualTo(200);
        JsonNode envelope = Json.parse(firstPage.body());
        assertThat(Json.elements(envelope.path("data"))).hasSize(2);
        assertThat(envelope.path("meta").path("total").asLong()).isEqualTo(3);
        assertThat(envelope.path("meta").path("page").asInt()).isZero();
        assertThat(envelope.path("meta").path("limit").asInt()).isEqualTo(2);

        assertThat(Json.elements(envelope.path("data")).stream()
            .map(row -> row.path("title").asString()).toList())
            .as("newest first")
            .containsExactly("Sync failure 2", "Sync failure 1");

        List<JsonNode> secondPage = inbox(member, "page=1&limit=2");
        assertThat(secondPage).hasSize(1);
        assertThat(secondPage.getFirst().path("title").asString()).isEqualTo("Sync failure 0");
    }

    @Test
    @DisplayName("A page or limit outside its range is refused rather than clamped")
    void badPagingIsRefused() {
        HttpResponse<String> negativePage = withoutRequestValidation(
            "page=-1 is outside the spec's own PageQuery minimum, which is the point: this "
                + "asserts what the SERVER answers to a query no generated client would send",
            () -> get(appUrl("/v1/notifications?page=-1"), member.accessToken()));
        assertThat(negativePage.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(negativePage.body())).isEqualTo("validation-error");

        HttpResponse<String> hugeLimit = withoutRequestValidation(
            "limit=5000 is outside the spec's own LimitQuery maximum, for the same reason",
            () -> get(appUrl("/v1/notifications?limit=5000"), member.accessToken()));
        assertThat(hugeLimit.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(hugeLimit.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("Marking one read clears it from the count and is idempotent")
    void markingReadIsIdempotent() {
        seedUnique(member.userId(), NotificationType.WEEKLY_DIGEST, "Your week");
        String id = inboxIds(member).getFirst();

        HttpResponse<String> first = patchJson(
            appUrl("/v1/notifications/" + id + "/read"), null, bearer(member));
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode body = Json.data(first.body());
        assertThat(body.path("read").asBoolean()).isTrue();
        assertThat(body.path("readAt").isNull()).isFalse();
        String readAt = body.path("readAt").asString();
        assertThat(unreadCount(member)).isZero();

        HttpResponse<String> again = patchJson(
            appUrl("/v1/notifications/" + id + "/read"), null, bearer(member));
        assertThat(again.statusCode())
            .as("acknowledging twice succeeds rather than conflicting")
            .isEqualTo(200);
        assertThat(Json.data(again.body()).path("readAt").asString())
            .as("readAt answers 'when did they first see this', not 'when did the client retry'")
            .isEqualTo(readAt);

        assertThat(inboxRow(member, id))
            .as("a read notification stays in the inbox until it is dismissed")
            .isNotNull();
    }

    @Test
    @DisplayName("read-all clears the badge and says how many rows it changed")
    void readAllReportsWhatItDid() {
        for (int index = 0; index < 3; index++) {
            seedUnique(member.userId(), NotificationType.BUDGET_THRESHOLD, "Budget " + index);
        }

        HttpResponse<String> response =
            postJson(appUrl("/v1/notifications/read-all"), null, bearer(member));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.data(response.body()).path("marked").asInt())
            .as("the number a client decrements its badge by, without a second fetch")
            .isEqualTo(3);
        assertThat(unreadCount(member)).isZero();
        assertThat(inbox(member)).hasSize(3);

        HttpResponse<String> again =
            postJson(appUrl("/v1/notifications/read-all"), null, bearer(member));
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(Json.data(again.body()).path("marked").asInt()).isZero();
    }

    @Test
    @DisplayName("DELETE is a dismissal: the list stops returning it, and it is idempotent")
    void deleteIsADismissal() {
        seedUnique(member.userId(), NotificationType.SYNC_FAILED, "Kept");
        seedUnique(member.userId(), NotificationType.SYNC_FAILED, "Dismissed");
        String dismissedId = inboxIds(member).getFirst();

        HttpResponse<String> response =
            delete(appUrl("/v1/notifications/" + dismissedId), null, bearer(member));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.parse(response.body()).path("success").asBoolean()).isTrue();
        assertThat(Json.parse(response.body()).has("data"))
            .as("the empty success omits data rather than sending null")
            .isFalse();

        assertThat(inboxIds(member))
            .as("dismissed rows leave the list")
            .doesNotContain(dismissedId);
        assertThat(inbox(member)).hasSize(1);
        assertThat(unreadCount(member))
            .as("dismissing also stops it counting towards the badge")
            .isEqualTo(1);

        assertThat(delete(appUrl("/v1/notifications/" + dismissedId), null, bearer(member))
            .statusCode())
            .as("dismissing twice succeeds")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("The row survives dismissal — the inbox is a record, not a list a member can erase")
    void aDismissedRowIsRetained() {
        seedUnique(member.userId(), NotificationType.WEEKLY_DIGEST, "Retained");
        String id = inboxIds(member).getFirst();
        delete(appUrl("/v1/notifications/" + id), null, bearer(member));

        Boolean dismissed = jdbcTemplate.queryForObject(
            "SELECT dismissed FROM in_app_notification WHERE id = ?::uuid", Boolean.class, id);
        assertThat(dismissed)
            .as("the row is hidden, not deleted: it is the record of what this server told this "
                + "member and a member must not be able to erase it by clicking")
            .isTrue();
    }

    @Test
    @DisplayName("An id that belongs to nothing is 404 on both by-id routes")
    void anUnknownIdIsNotFound() {
        UUID unknown = unknownId();

        HttpResponse<String> read = patchJson(
            appUrl("/v1/notifications/" + unknown + "/read"), null, bearer(member));
        assertThat(read.statusCode()).isEqualTo(404);
        assertThat(Json.errorCode(read.body())).isEqualTo("not-found");

        HttpResponse<String> dismiss =
            delete(appUrl("/v1/notifications/" + unknown), null, bearer(member));
        assertThat(dismiss.statusCode()).isEqualTo(404);
        assertThat(Json.errorCode(dismiss.body())).isEqualTo("not-found");
    }

    @Test
    @DisplayName("A household mate's notification is 404, not 403 — the inbox is not shared")
    void aHouseholdMateIsRefused() {
        // The opposite of every ledger route on this API. The ledger is shared by the owner's
        // decision; a notification is addressed to one person.
        Session admin = loginBootstrapAdmin();
        Session mate = inviteAndRegister(admin, "inbox-mate");
        seedUnique(member.userId(), NotificationType.BUDGET_THRESHOLD, "Private");
        String id = inboxIds(member).getFirst();

        assertThat(mate.householdId()).isEqualTo(member.householdId());
        assertThat(inbox(mate))
            .as("a mate does not see another member's notifications at all")
            .noneMatch(row -> id.equals(row.path("id").asString()));
        assertThat(patchJson(appUrl("/v1/notifications/" + id + "/read"), null, bearer(mate))
            .statusCode()).isEqualTo(404);
        assertThat(delete(appUrl("/v1/notifications/" + id), null, bearer(mate)).statusCode())
            .isEqualTo(404);
    }
}
