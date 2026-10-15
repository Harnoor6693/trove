package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ai.AiGateway;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * {@code /v1/ai/*} end to end (§4.11): the settings round-trip, the write-only API key, the
 * {@code EGRESS_SETTING_CHANGE} audit trail, and the per-user isolation that makes this resource
 * different from every other one in the application.
 *
 * <p><strong>Why the endpoint is an IP literal.</strong> {@code OutboundUrlPolicy} resolves the
 * host and refuses anything private, and it does so at save time as well as on every call. A
 * hostname would need DNS, which an offline build does not have; {@code localhost} is loopback and
 * is refused by design. {@code 203.0.113.10} is RFC 5737 TEST-NET-3: a literal, so
 * {@code InetAddress} answers without a lookup, and public as far as the policy is concerned, so
 * the save succeeds. Nothing in this class actually reaches it except the two tests that are about
 * what happens when an endpoint cannot be reached.
 */
class AiSettingsIT extends AuthFlowSupport {

    @Autowired
    private AiGateway gateway;

    private static final String ENDPOINT = "https://203.0.113.10/v1";
    private static final String API_KEY = "sk-integration-test-key-9f2c";

    @Test
    @DisplayName("a member who has never configured AI reads the defaults, not a 404")
    void unconfiguredReadsDefaults() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-defaults");

        HttpResponse<String> response = get(appUrl("/v1/ai/settings"), member.accessToken());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = Json.data(response.body());
        assertThat(data.path("enabled").asBoolean()).isFalse();
        assertThat(data.path("endpointUrl").isNull()).isTrue();
        assertThat(data.path("hasApiKey").asBoolean()).isFalse();
        assertThat(data.path("privacyTier").asString()).isEqualTo("CLOUD_OK");
        assertThat(data.path("updatedAt").isNull())
            .as("nothing has been written, so there is nothing to timestamp")
            .isTrue();
    }

    @Test
    @DisplayName("the API key goes in, is stored encrypted, and never comes back out")
    void theApiKeyIsWriteOnly() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-key");

        HttpResponse<String> saved = putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, ENDPOINT, API_KEY, "llama3", "llava", "CLOUD_OK"),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(saved.body())
            .as("the key must not appear anywhere in the response, masked or otherwise")
            .doesNotContain(API_KEY);
        assertThat(Json.data(saved.body()).path("hasApiKey").asBoolean()).isTrue();

        HttpResponse<String> read = get(appUrl("/v1/ai/settings"), member.accessToken());
        assertThat(read.body()).doesNotContain(API_KEY);
        JsonNode data = Json.data(read.body());
        assertThat(data.path("endpointUrl").asString()).isEqualTo(ENDPOINT);
        assertThat(data.path("model").asString()).isEqualTo("llama3");
        assertThat(data.path("visionModel").asString()).isEqualTo("llava");
        assertThat(data.path("enabled").asBoolean()).isTrue();
        assertThat(data.path("hasApiKey").asBoolean()).isTrue();

        String stored = jdbcTemplate.queryForObject(
            "SELECT api_key_enc FROM ai_settings WHERE user_id = ?", String.class,
            member.userId());
        assertThat(stored)
            .as("the column holds versioned AES-GCM ciphertext; a dump must not be a set of keys")
            .startsWith("v1:")
            .doesNotContain(API_KEY);
    }

    @Test
    @DisplayName("a save with no apiKey keeps the stored one; DELETE removes it and nothing else")
    void theKeyIsKeptOnSaveAndRemovedByItsOwnRoute() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-key-lifecycle");
        String bearer = "Bearer " + member.accessToken();

        putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, ENDPOINT, API_KEY, "llama3", null, "CLOUD_OK"),
            "Authorization", bearer);

        HttpResponse<String> resaved = putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, ENDPOINT, null, "qwen2.5:14b", null, "CLOUD_OK"),
            "Authorization", bearer);
        assertThat(Json.data(resaved.body()).path("hasApiKey").asBoolean())
            .as("a write-only field is not in the GET, so a client cannot send it back")
            .isTrue();
        assertThat(Json.data(resaved.body()).path("model").asString()).isEqualTo("qwen2.5:14b");

        HttpResponse<String> cleared = delete(appUrl("/v1/ai/settings/api-key"), null,
            "Authorization", bearer);
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(200);
        assertThat(Json.parse(cleared.body()).has("data"))
            .as("an empty success omits data rather than sending null")
            .isFalse();

        JsonNode after = Json.data(get(appUrl("/v1/ai/settings"), member.accessToken()).body());
        assertThat(after.path("hasApiKey").asBoolean()).isFalse();
        assertThat(after.path("endpointUrl").asString())
            .as("an endpoint that needs no key is an ordinary configuration, not a broken one")
            .isEqualTo(ENDPOINT);
        assertThat(after.path("enabled").asBoolean()).isTrue();

        assertThat(delete(appUrl("/v1/ai/settings/api-key"), null, "Authorization", bearer)
            .statusCode())
            .as("idempotent: answering differently would say whether a key was configured")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("changing the endpoint writes EGRESS_SETTING_CHANGE, with the host and no more")
    void anEgressChangeIsAudited() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-egress-audit");
        String bearer = "Bearer " + member.accessToken();

        putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, ENDPOINT + "/tenant/secret-path", API_KEY, "llama3", null,
                "CLOUD_OK"),
            "Authorization", bearer);

        List<String> metadata = jdbcTemplate.queryForList(
            "SELECT metadata::text FROM security_event WHERE user_id = ? "
                + "AND type = 'EGRESS_SETTING_CHANGE'", String.class, member.userId());
        assertThat(metadata).hasSize(1);
        assertThat(metadata.get(0))
            .contains("203.0.113.10")
            .as("the row is readable by its subject; an endpoint path routinely carries a token")
            .doesNotContain("secret-path")
            .doesNotContain(API_KEY);

        HttpResponse<String> events = get(appUrl("/v1/me/security-events"), member.accessToken());
        assertThat(Json.elements(Json.data(events.body())).stream()
            .map(event -> event.path("type").asString()))
            .contains("EGRESS_SETTING_CHANGE");
    }

    @Test
    @DisplayName("a settings change that touches no egress field writes no event")
    void aNonEgressChangeIsNotAudited() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-no-audit");
        String bearer = "Bearer " + member.accessToken();

        putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, ENDPOINT, null, "llama3", null, "CLOUD_OK"),
            "Authorization", bearer);
        long afterFirstSave = egressEventCount(member.userId());

        putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, ENDPOINT, null, "llama3", "llava", "LOCAL_ONLY"),
            "Authorization", bearer);

        assertThat(egressEventCount(member.userId()))
            .as("a vision model and a privacy tier change nothing about where data may go")
            .isEqualTo(afterFirstSave);
    }

    @Test
    @DisplayName("turning AI on with no endpoint is a field error, not a banner")
    void enablingWithoutAnEndpointIsRefused() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-invalid");

        HttpResponse<String> response = putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, null, null, "llama3", null, "CLOUD_OK"),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(Json.elements(Json.parse(response.body()).path("error").path("fieldErrors"))
            .stream().map(field -> field.path("field").asString()))
            .containsExactly("endpointUrl");
    }

    @Test
    @DisplayName("test and models answer ai-disabled while nothing is configured")
    void unconfiguredCannotBeTested() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-unconfigured");
        String bearer = "Bearer " + member.accessToken();

        HttpResponse<String> test = postJson(appUrl("/v1/ai/settings/test"), null,
            "Authorization", bearer);
        assertThat(test.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(test.body())).isEqualTo("ai-disabled");

        HttpResponse<String> models = get(appUrl("/v1/ai/models"), member.accessToken());
        assertThat(models.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(models.body())).isEqualTo("ai-disabled");
    }

    @Test
    @DisplayName("an endpoint that cannot be reached is a typed AI failure, never a Trove 500")
    void anUnreachableEndpointIsTyped() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-unreachable");
        String bearer = "Bearer " + member.accessToken();

        // The lowest connect timeout the schema allows, because 203.0.113.10 is a documentation
        // range that nothing routes to: the point is which exception comes back, not how long it
        // takes to come back.
        putJson(appUrl("/v1/ai/settings"),
            Json.object("enabled", true, "endpointUrl", ENDPOINT, "model", "llama3",
                "allowPrivateEndpoint", false, "privacyTier", "CLOUD_OK",
                "connectTimeoutMs", 100, "readTimeoutMs", 1000),
            "Authorization", bearer);

        HttpResponse<String> test = postJson(appUrl("/v1/ai/settings/test"), null,
            "Authorization", bearer);

        assertThat(test.statusCode())
            .as("502 if the connection was refused outright, 504 if it timed out. Body: %s",
                test.body())
            .isIn(502, 504);
        assertThat(Json.errorCode(test.body())).isIn("ai-unreachable", "ai-timeout");

        Boolean lastTestOk = jdbcTemplate.queryForObject(
            "SELECT last_test_ok FROM ai_settings WHERE user_id = ?", Boolean.class,
            member.userId());
        assertThat(lastTestOk)
            .as("the failure is written even though the request ended in an exception")
            .isFalse();

        Long audited = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_audit_log WHERE user_id = ? AND operation = 'TEST' "
                + "AND success = false", Long.class, member.userId());
        assertThat(audited).isPositive();
    }

    @Test
    @DisplayName("the model list works with no model saved; the connection test still needs one")
    void theModelListIsReachableBeforeAModelIsChosen() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-model-picker");
        String bearer = "Bearer " + member.accessToken();

        // What a member's settings look like the moment before they pick a model: an endpoint,
        // AI still off, and nothing in the model box. The short timeouts are because nothing
        // routes to 203.0.113.10 and the point is which answer comes back, not how long it takes.
        putJson(appUrl("/v1/ai/settings"),
            Json.object("enabled", false, "endpointUrl", ENDPOINT, "allowPrivateEndpoint", false,
                "privacyTier", "CLOUD_OK", "connectTimeoutMs", 100, "readTimeoutMs", 1000),
            "Authorization", bearer);

        HttpResponse<String> models = get(appUrl("/v1/ai/models"), member.accessToken());
        assertThat(Json.errorCode(models.body()))
            .as("the route exists so a member can pick a model; demanding one first makes it "
                + "unreachable by the only person who needs it. Body: %s", models.body())
            .isNotEqualTo("ai-disabled");
        assertThat(models.statusCode())
            .as("it got as far as the network, which is all this asserts")
            .isIn(502, 504);

        HttpResponse<String> test = postJson(appUrl("/v1/ai/settings/test"), null,
            "Authorization", bearer);
        assertThat(test.statusCode())
            .as("the test verifies that the endpoint serves the chosen model, so it needs one")
            .isEqualTo(409);
        assertThat(Json.errorCode(test.body())).isEqualTo("ai-disabled");
    }

    @Test
    @DisplayName("a blank model is a field error, not a silent clear of a working model")
    void aBlankModelIsRefused() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "ai-blank-model");
        String bearer = "Bearer " + member.accessToken();

        putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, ENDPOINT, null, "llama3", null, "CLOUD_OK"),
            "Authorization", bearer);

        HttpResponse<String> response = withoutRequestValidation(
            "the spec says minLength: 1 on `model`, so a blank one is by definition a request no "
                + "client sends; what is under test is that the server refuses it as well",
            () -> putJson(appUrl("/v1/ai/settings"),
                settingsBody(true, ENDPOINT, null, "", null, "CLOUD_OK"),
                "Authorization", bearer));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(Json.elements(Json.parse(response.body()).path("error").path("fieldErrors"))
            .stream().map(field -> field.path("field").asString()))
            .containsExactly("model");
        assertThat(Json.data(get(appUrl("/v1/ai/settings"), member.accessToken()).body())
            .path("model").asString())
            .as("and the stored model is still there")
            .isEqualTo("llama3");
    }

    @Test
    @DisplayName("a household mate sees their own settings, never yours")
    void settingsArePerUserNotPerHousehold() {
        Session admin = loginBootstrapAdmin();
        Session one = inviteAndRegister(admin, "ai-mate-one");
        Session two = inviteAndRegister(admin, "ai-mate-two");
        assertThat(one.householdId())
            .as("this assertion is about two members of the SAME household")
            .isEqualTo(two.householdId());

        putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, ENDPOINT, API_KEY, "llama3", null, "LOCAL_ONLY"),
            "Authorization", "Bearer " + one.accessToken());

        HttpResponse<String> theirs = get(appUrl("/v1/ai/settings"), two.accessToken());
        JsonNode data = Json.data(theirs.body());
        assertThat(data.path("endpointUrl").isNull())
            .as("the shared ledger is shared; the AI endpoint and its key are not (decision 9)")
            .isTrue();
        assertThat(data.path("hasApiKey").asBoolean()).isFalse();
        assertThat(data.path("enabled").asBoolean()).isFalse();
        assertThat(theirs.body()).doesNotContain(API_KEY);

        // And the mate's own save leaves the first member's row untouched.
        putJson(appUrl("/v1/ai/settings"),
            settingsBody(false, null, null, null, null, "CLOUD_OK"),
            "Authorization", "Bearer " + two.accessToken());
        JsonNode mine = Json.data(get(appUrl("/v1/ai/settings"), one.accessToken()).body());
        assertThat(mine.path("endpointUrl").asString()).isEqualTo(ENDPOINT);
        assertThat(mine.path("enabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a member who has left is never called on behalf of, during the deletion grace too")
    void aDeletedMembersSettingsAreNotUsed() {
        Session admin = loginBootstrapAdmin();
        Session member = inviteAndRegister(admin, "ai-leaver");
        putJson(appUrl("/v1/ai/settings"),
            settingsBody(true, ENDPOINT, API_KEY, "llama3", "llava", "LOCAL_ONLY"),
            "Authorization", "Bearer " + member.accessToken());
        assertThat(gateway.forUser(member.userId()).isEnabled())
            .as("the settings are live while the member is, or the assertion below proves nothing")
            .isTrue();

        HttpResponse<String> deleted = delete(appUrl("/v1/admin/users/" + member.userId()),
            null, "Authorization", "Bearer " + admin.accessToken());
        assertThat(deleted.statusCode()).as(deleted.body()).isLessThan(300);

        assertThat(gateway.forUser(member.userId()).isEnabled())
            .as("B3-14: the receipt sweep re-runs a stuck receipt as its uploader, and spent a "
                + "deleted member's key at their endpoint for the seven days of the grace")
            .isFalse();
    }

    @Test
    @DisplayName("the AI routes need a session")
    void anonymousIsRefused() {
        assertThat(get(appUrl("/v1/ai/settings")).statusCode()).isEqualTo(401);
        assertThat(get(appUrl("/v1/ai/models")).statusCode()).isEqualTo(401);
        assertThat(postJson(appUrl("/v1/ai/settings/test"), null).statusCode()).isEqualTo(401);
        assertThat(delete(appUrl("/v1/ai/settings/api-key"), null).statusCode()).isEqualTo(401);
    }

    private long egressEventCount(UUID userId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM security_event WHERE user_id = ? "
                + "AND type = 'EGRESS_SETTING_CHANGE'", Long.class, userId);
        return count == null ? 0L : count;
    }

    private static String settingsBody(boolean enabled, String endpointUrl, String apiKey,
                                       String model, String visionModel, String privacyTier) {
        return Json.object(
            "enabled", enabled,
            "endpointUrl", endpointUrl,
            "apiKey", apiKey,
            "model", model,
            "visionModel", visionModel,
            "allowPrivateEndpoint", false,
            "privacyTier", privacyTier);
    }
}
