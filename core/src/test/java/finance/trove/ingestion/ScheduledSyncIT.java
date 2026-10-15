package finance.trove.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.ingestion.simplefin.TestSimplefinBridge;
import finance.trove.ingestion.simplefin.dto.SimplefinAccount;
import finance.trove.notifications.NotificationSupport;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The scheduled bank sync end to end (C-01r): the admin switch over HTTP, a SimpleFIN account
 * connected through {@link TestSimplefinBridge}, {@link ScheduledSyncJob#runOnce()} through the
 * real {@link SyncOrchestrator}, and the SYNC_FAILED alerts it does — and does not — write.
 *
 * <p>The fetch itself is served by {@link TestBankSource}, which takes precedence over the real
 * SimpleFIN source in every integration context (its javadoc says why): the egress policy refuses
 * every address a WireMock bridge could listen on, and the bridge client's own network behaviour
 * is covered by {@code SimplefinBridgeClientTest} against WireMock.
 */
class ScheduledSyncIT extends NotificationSupport {

    private static final String DEAD = "The institution needs you to sign in again.";

    @Autowired
    private ScheduledSyncJob job;

    @Autowired
    private TestSimplefinBridge bridge;

    @Autowired
    private TestBankSource source;

    private Session admin;
    private Session member;
    private String accountId;

    @BeforeEach
    void connectASimplefinAccount() {
        bridge.reset();
        source.reset();
        admin = loginBootstrapAdmin();
        member = inviteAndRegister(admin, "scheduled-sync");

        String suffix = UUID.randomUUID().toString();
        bridge.returning("https://bridge.example.com/simplefin/ACCESS-" + suffix,
            List.of(new SimplefinAccount("chq-" + suffix, "Chequing", "CAD", null, List.of())));
        HttpResponse<String> exchange = postJson(appUrl("/v1/accounts/exchange-setup-token"),
            Json.object("sourceKind", "SIMPLEFIN", "setupToken", Base64.getEncoder()
                .encodeToString(("https://bridge.example.com/claim/" + suffix)
                    .getBytes(StandardCharsets.UTF_8))),
            bearer(member));
        assertThat(exchange.statusCode()).as(exchange.body()).isEqualTo(201);
        accountId = Json.elements(Json.data(exchange.body()).path("accountIds")).get(0)
            .asString();
    }

    @AfterEach
    void switchOff() {
        scheduledSync(false);
        bridge.reset();
        source.releaseFetch();
        source.reset();
    }

    private HttpResponse<String> scheduledSync(boolean on) {
        return putJson(appUrl("/v1/admin/settings"), Json.object("scheduledSyncEnabled", on),
            bearer(admin));
    }

    private int runs() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM sync_log WHERE account_id = ?",
            Integer.class, UUID.fromString(accountId));
    }

    private List<JsonNode> alerts() {
        return inbox(member).stream()
            .filter(row -> "SYNC_FAILED".equals(row.path("type").asString()))
            .filter(row -> ("/accounts/" + accountId).equals(row.path("actionUrl").asString()))
            .toList();
    }

    private String syncStatus() {
        return jdbcTemplate.queryForObject(
            "SELECT sync_status FROM connected_account WHERE id = ?", String.class,
            UUID.fromString(accountId));
    }

    @Test
    @DisplayName("The switch is off by default, and while it is off a run syncs nothing")
    void offByDefaultAndOffMeansNothing() {
        JsonNode settings = Json.data(get(appUrl("/v1/admin/settings"), admin.accessToken())
            .body());
        assertThat(settings.path("scheduledSyncEnabled").asBoolean(true)).isFalse();
        int before = runs();
        int fetches = source.fetchCount();

        assertThat(job.runOnce()).isEqualTo(new ScheduledSyncJob.Result(0, 0, 0));

        assertThat(runs()).isEqualTo(before);
        assertThat(source.fetchCount()).isEqualTo(fetches);
    }

    @Test
    @DisplayName("Switched on, a run syncs the account through the same pipeline as the button")
    void onMeansEveryConnectedAccountIsSynced() {
        HttpResponse<String> on = scheduledSync(true);
        assertThat(on.statusCode()).isEqualTo(200);
        assertThat(Json.data(on.body()).path("scheduledSyncEnabled").asBoolean()).isTrue();
        assertThat(Json.data(on.body()).path("registrationOpen").asBoolean())
            .as("a partial update leaves the other flag alone")
            .isFalse();
        int before = runs();

        job.runOnce();

        assertThat(runs()).isEqualTo(before + 1);
        assertThat(syncStatus()).isEqualTo("OK");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT user_id FROM sync_log WHERE account_id = ? ORDER BY started_at DESC LIMIT 1",
            UUID.class, UUID.fromString(accountId)))
            .as("the run acts as the member who connected the account")
            .isEqualTo(member.userId());
        assertThat(alerts()).isEmpty();
    }

    @Test
    @DisplayName("A dead connection is announced once, and again only after it recovered")
    void aDeadConnectionIsAnnouncedOncePerFailure() {
        scheduledSync(true);

        source.failingWith(DEAD);
        job.runOnce();
        assertThat(syncStatus()).isEqualTo("ERROR");
        assertThat(alerts()).as("healthy to failing is news").hasSize(1);
        assertThat(alerts().get(0).path("body").asString()).contains(DEAD);

        job.runOnce();
        job.runOnce();
        assertThat(alerts())
            .as("the same dead connection on the next scheduled runs is not news again")
            .hasSize(1);

        source.returning(List.of());
        job.runOnce();
        assertThat(syncStatus()).isEqualTo("OK");
        assertThat(alerts()).hasSize(1);

        source.failingWith(DEAD);
        job.runOnce();
        assertThat(alerts())
            .as("recovered and failed again — even on the same day — is news again")
            .hasSize(2);
    }

    @Test
    @DisplayName("A manual sync keeps its daily alert, scheduled sync or not")
    void aManualSyncIsUnchanged() {
        scheduledSync(true);
        source.failingWith(DEAD);
        job.runOnce();
        assertThat(alerts()).hasSize(1);

        HttpResponse<String> manual = postJson(appUrl("/v1/accounts/" + accountId + "/sync"),
            null, bearer(member));
        assertThat(manual.statusCode()).isEqualTo(200);

        assertThat(alerts())
            .as("the manual run is keyed by the member's day, so it is told as it always was")
            .hasSize(2);
    }
}
