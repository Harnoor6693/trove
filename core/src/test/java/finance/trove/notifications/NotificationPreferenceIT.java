package finance.trove.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET}/{@code PUT} {@code /v1/notifications/preferences} (§4.9).
 *
 * <p>Two claims are asserted here that no unit test can make: that a {@code GET} really does
 * create the row (the digest enumerates its recipients from that table, so a member without one
 * would silently never be in it), and that the response carries no push field on the WIRE — the
 * spec's schema is closed, so an accidental one would fail this test's own conformance check
 * before any assertion below ran.
 */
class NotificationPreferenceIT extends NotificationSupport {

    private Session member;

    @BeforeEach
    void signIn() {
        member = inviteAndRegister(loginBootstrapAdmin(), "prefs-member");
    }

    private JsonNode read() {
        HttpResponse<String> response =
            get(appUrl("/v1/notifications/preferences"), member.accessToken());
        assertThat(response.statusCode())
            .as("reading preferences failed: %s", response.body())
            .isEqualTo(200);
        return Json.data(response.body());
    }

    private HttpResponse<String> write(String json) {
        return putJson(appUrl("/v1/notifications/preferences"), json, bearer(member));
    }

    @Test
    @DisplayName("GET creates the row on first access, with exactly V31's defaults")
    void firstReadCreatesTheDefaults() {
        Integer before = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_preference WHERE user_id = ?", Integer.class,
            member.userId());

        JsonNode preferences = read();

        assertThat(before).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_preference WHERE user_id = ?", Integer.class,
            member.userId()))
            .as("persisted, not returned transient: the weekly digest reads its recipients from "
                + "this table")
            .isEqualTo(1);

        assertThat(preferences.path("inAppEnabled").asBoolean()).isTrue();
        assertThat(preferences.path("emailEnabled").asBoolean())
            .as("off by default — the channel cannot deliver until an operator configures SMTP")
            .isFalse();
        assertThat(preferences.path("emailAddress").isNull()).isTrue();
        assertThat(preferences.path("weeklyDigestEnabled").asBoolean()).isTrue();
        assertThat(preferences.path("subscriptionAlertEnabled").asBoolean()).isTrue();
        assertThat(preferences.path("syncFailureAlertEnabled").asBoolean()).isTrue();
        assertThat(preferences.path("budgetAlertThresholdPercent").asInt()).isEqualTo(90);
        assertThat(preferences.path("updatedAt").asString()).isNotBlank();
    }

    @Test
    @DisplayName("emailAvailable is false while the install has no email channel (F1-B8)")
    void emailIsUnavailableWithoutSmtp() {
        // Every shipped configuration leaves SMTP off, so the EMAIL channel bean does not exist
        // and nothing would ever be mailed. The clients hide "Also email me" on exactly this flag.
        assertThat(read().path("emailAvailable").isBoolean()).isTrue();
        assertThat(read().path("emailAvailable").asBoolean()).isFalse();

        HttpResponse<String> written = write("{\"weeklyDigestEnabled\":false}");
        assertThat(written.statusCode()).as("%s", written.body()).isEqualTo(200);
        assertThat(Json.data(written.body()).path("emailAvailable").asBoolean())
            .as("the PUT echo is the same resource, flag included")
            .isFalse();
    }

    @Test
    @DisplayName("The response carries no push field of any kind")
    void thereIsNoPushFieldOnTheWire() {
        // The spec's response schema is closed, so an accidental field would already have failed
        // OpenApiConformance before this assertion. Naming the four the source carried makes the
        // owner's decision legible in the test suite as well as in the schema.
        JsonNode preferences = read();

        assertThat(preferences.propertyNames())
            .doesNotContain("pushEnabled", "apnsToken", "fcmToken", "taxReminderEnabled",
                "pushTokens", "devices");
    }

    @Test
    @DisplayName("PUT is a partial patch: an omitted field is left exactly as it was")
    void putIsPartial() {
        write(Json.object("weeklyDigestEnabled", false, "budgetAlertThresholdPercent", 75));

        HttpResponse<String> second = write(Json.object("subscriptionAlertEnabled", false));

        assertThat(second.statusCode()).isEqualTo(200);
        JsonNode after = Json.data(second.body());
        assertThat(after.path("subscriptionAlertEnabled").asBoolean()).isFalse();
        assertThat(after.path("weeklyDigestEnabled").asBoolean())
            .as("not mentioned by the second call, so unchanged")
            .isFalse();
        assertThat(after.path("budgetAlertThresholdPercent").asInt()).isEqualTo(75);
        assertThat(after.path("inAppEnabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("An explicit null clears the email address; omitting it does not")
    void anExplicitNullClearsTheAddress() {
        write(Json.object("emailEnabled", true, "emailAddress", "alerts@trove.test"));
        assertThat(read().path("emailAddress").asString()).isEqualTo("alerts@trove.test");

        write(Json.object("inAppEnabled", false));
        assertThat(read().path("emailAddress").asString())
            .as("omitted is not cleared")
            .isEqualTo("alerts@trove.test");

        HttpResponse<String> cleared = write(Json.object("emailAddress", null));
        assertThat(cleared.statusCode()).isEqualTo(200);
        assertThat(Json.data(cleared.body()).path("emailAddress").isNull())
            .as("an explicit null is the only way to unset an address")
            .isTrue();
        assertThat(Json.data(cleared.body()).path("emailEnabled").asBoolean())
            .as("clearing the address does not silently switch the channel off")
            .isTrue();
    }

    @Test
    @DisplayName("An address that is not an address is 400 validation-error naming the field")
    void aBadAddressIsRefused() {
        HttpResponse<String> response = withoutRequestValidation(
            "the spec's emailAddress pattern rejects this value, which is the point: the test "
                + "asserts what the SERVER answers to a body no generated client would send",
            () -> write(Json.object("emailAddress", "not-an-address")));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(Json.elements(
            Json.parse(response.body()).path("error").path("fieldErrors")))
            .anyMatch(entry -> "emailAddress".equals(entry.path("field").asString()));
    }

    @Test
    @DisplayName("A threshold outside 1-200 is 400, and the stored value is untouched")
    void anImpossibleThresholdIsRefused() {
        write(Json.object("budgetAlertThresholdPercent", 60));

        HttpResponse<String> tooLow = withoutRequestValidation(
            "0 is outside the spec's own minimum, so the request is deliberately non-conforming",
            () -> write(Json.object("budgetAlertThresholdPercent", 0)));
        assertThat(tooLow.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(tooLow.body())).isEqualTo("validation-error");

        HttpResponse<String> tooHigh = withoutRequestValidation(
            "201 is outside the spec's own maximum",
            () -> write(Json.object("budgetAlertThresholdPercent", 201)));
        assertThat(tooHigh.statusCode()).isEqualTo(400);

        assertThat(read().path("budgetAlertThresholdPercent").asInt())
            .as("a refused write changes nothing")
            .isEqualTo(60);
    }

    @Test
    @DisplayName("200 is a legal threshold — a member may ask to hear only when well over budget")
    void twoHundredIsLegal() {
        HttpResponse<String> response = write(Json.object("budgetAlertThresholdPercent", 200));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.data(response.body()).path("budgetAlertThresholdPercent").asInt())
            .isEqualTo(200);
    }

    @Test
    @DisplayName("A household mate's preferences are their own — each member sees only theirs")
    void preferencesAreNotSharedWithinTheHousehold() {
        Session mate = inviteAndRegister(loginBootstrapAdmin(), "prefs-mate");
        write(Json.object("budgetAlertThresholdPercent", 55));

        HttpResponse<String> theirs =
            get(appUrl("/v1/notifications/preferences"), mate.accessToken());

        assertThat(Json.data(theirs.body()).path("budgetAlertThresholdPercent").asInt())
            .as("the ledger is shared; a member's notification settings are not")
            .isEqualTo(90);
    }

    @Test
    @DisplayName("Two threads reaching a member with no row create exactly one, and neither fails")
    void theFirstEverRowIsCreatedOnceUnderConcurrency() throws Exception {
        // getOrCreate was a read-then-insert with no upsert and no lock, against a table whose
        // primary key IS the user id — so two producers reaching a member who had never had a row
        // both found the Optional empty and both INSERTed, and the loser took a primary-key
        // violation that rolled back its caller's whole transaction. That is reachable: the budget
        // listener walks every household member inside its own REQUIRES_NEW transaction, so two
        // crossings committing at once in one household both touch the same never-seen members,
        // and the rollback costs every notification that pass had already written.
        Session fresh = inviteAndRegister(loginBootstrapAdmin(), "prefs-race");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Integer> read = () -> {
            start.await(5, TimeUnit.SECONDS);
            return get(appUrl("/v1/notifications/preferences"), fresh.accessToken()).statusCode();
        };
        try {
            Future<Integer> first = pool.submit(read);
            Future<Integer> second = pool.submit(read);
            start.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                .as("the loser of the INSERT ... ON CONFLICT DO NOTHING reads the winner's row "
                    + "instead of taking a primary-key violation")
                .containsExactly(200, 200);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_preference WHERE user_id = ?", Integer.class,
            fresh.userId()))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("An unknown field is accepted and ignored, as every request schema here allows")
    void unknownFieldsAreIgnored() {
        // Including the push fields the source carried: a client generated from an older schema
        // must not start failing because a field was removed from the server.
        // No conformance opt-out: request schemas on this API are deliberately OPEN, because the
        // server runs with FAIL_ON_UNKNOWN_PROPERTIES disabled and a closed request schema would
        // describe a stricter server than the one that ships. So this body conforms — and that
        // IS the contract being asserted.
        HttpResponse<String> response = write(Json.object("pushEnabled", true,
            "apnsToken", "deadbeef", "inAppEnabled", false));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(Json.data(response.body()).path("inAppEnabled").asBoolean()).isFalse();
        assertThat(Json.data(response.body()).propertyNames()).doesNotContain("pushEnabled");
    }
}
