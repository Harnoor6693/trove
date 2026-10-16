package finance.trove.ingestion.simplefin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.app.support.LogCapture;
import finance.trove.ingestion.TestBankSource;
import finance.trove.ingestion.simplefin.dto.SimplefinAccount;
import java.net.http.HttpResponse;
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
 * {@code POST /v1/accounts/exchange-setup-token} end to end
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.3, §6 M0; {@code 01-FINDINGS.md} G2).
 *
 * <p>Three things are proved here that no unit test can: that a re-exchange REUSES the accounts it
 * created rather than duplicating them and every transaction on them (§6 M0, blocker); that the
 * access URL is encrypted into {@code config_encrypted} and reaches no response body and no log
 * line; and that a token decoding to an address the egress policy refuses is a 400 the member can
 * act on rather than a request the server makes.
 */
class SimpleFinIT extends LedgerSupport {

    @Autowired
    private TestSimplefinBridge bridge;

    @Autowired
    private SimplefinSource simplefinSource;

    @Autowired
    private TestBankSource source;

    private Session admin;

    @BeforeEach
    void signIn() {
        bridge.reset();
        source.reset();
        admin = loginBootstrapAdmin();
    }

    @AfterEach
    void release() {
        bridge.reset();
        source.releaseFetch();
        source.reset();
    }

    @Test
    @DisplayName("An exchange connects one account per bridge account and syncs each of them")
    void anExchangeConnectsEveryBridgeAccount() {
        String suffix = UUID.randomUUID().toString();
        bridge.returning(accessUrl(suffix), List.of(
            bridgeAccount("chq-" + suffix, "Chequing", "CAD"),
            bridgeAccount("sav-" + suffix, "Savings", "USD")));

        JsonNode data = Json.data(exchange(token("https://bridge.example.com/claim/" + suffix))
            .body());

        assertThat(Json.elements(data.path("accountIds")))
            .as("web's Onboarding reads accountIds.length to tell the member how many "
                + "accounts were connected (§4.3/§4.13)")
            .hasSize(2);
        assertThat(Json.elements(data.path("syncLogs"))).hasSize(2);
        assertThat(Json.elements(data.path("syncLogs")).get(0).path("sourceKind").asString())
            .isEqualTo("SIMPLEFIN");
        assertThat(bridge.lastRequestedFrom())
            .as("the window is sent to the bridge rather than the whole history being filtered "
                + "in memory")
            .isNotNull();
    }

    @Test
    @DisplayName("§6 M0: a second exchange reuses the accounts, it does not duplicate them")
    void aSecondExchangeReusesTheSameAccounts() {
        String suffix = UUID.randomUUID().toString();
        List<SimplefinAccount> accounts = List.of(
            bridgeAccount("chq-" + suffix, "Chequing", "CAD"),
            bridgeAccount("sav-" + suffix, "Savings", "CAD"));
        bridge.returning(accessUrl(suffix), accounts);

        List<String> first = accountIds(exchange(token("https://bridge.example.com/claim/a")));
        int accountCountAfterFirst = accountCount();

        bridge.returning(accessUrl(suffix + "-rotated"), accounts);
        List<String> second = accountIds(exchange(token("https://bridge.example.com/claim/b")));

        assertThat(second)
            .as("a token rotation must not create a second copy of every account and, on its "
                + "first sync, a second copy of every transaction on it")
            .containsExactlyElementsOf(first);
        assertThat(accountCount()).isEqualTo(accountCountAfterFirst);
    }

    @Test
    @DisplayName("The access URL is encrypted at rest and appears in no response and no log")
    void theAccessUrlNeverEscapes() {
        String suffix = UUID.randomUUID().toString();
        String secret = accessUrl(suffix);
        bridge.returning(secret, List.of(bridgeAccount("chq-" + suffix, "Chequing", "CAD")));

        String body;
        List<String> lines;
        try (LogCapture logs = LogCapture.start()) {
            HttpResponse<String> response = exchange(token("https://bridge.example.com/claim/c"));
            body = response.body();
            lines = logs.lines();
        }
        String accountId = accountIds(body).get(0);

        assertThat(body).doesNotContain(secret);
        assertThat(lines).noneMatch(line -> line.contains(secret));
        assertThat(get(appUrl("/v1/accounts/" + accountId), admin.accessToken()).body())
            .as("AccountResponse has no field the credential could appear in at all")
            .doesNotContain(secret);

        String stored = jdbcTemplate.queryForObject(
            "SELECT config_encrypted FROM connected_account WHERE id = ?::uuid", String.class,
            accountId);
        assertThat(stored)
            .as("the credential is encrypted into config_encrypted immediately, in the one "
                + "column allowed to hold it")
            .isNotNull()
            .doesNotContain(secret)
            .startsWith("v1");
    }

    @Test
    @DisplayName("G2: a token decoding to a private address is 400, and echoes neither")
    void aRefusedUrlIsFourHundred() {
        String setupToken = token("https://10.0.0.7/simplefin/claim/SECRET-TOKEN-VALUE");

        HttpResponse<String> response = exchange(setupToken);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("outbound-url-rejected");
        assertThat(Json.parse(response.body()).path("error").path("detail").asString())
            .as("the message is the one generic refusal: never the address, nor its range (B3-10)")
            .contains("only to addresses this server is allowed to connect to")
            .doesNotContain("private");
        assertThat(response.body())
            .doesNotContain("SECRET-TOKEN-VALUE")
            .doesNotContain("10.0.0.7/simplefin");
    }

    @Test
    @DisplayName("A token that is not base64 is a 400 that never quotes the token")
    void aMalformedTokenIsFourHundred() {
        HttpResponse<String> response = exchange("!!! not base64 !!!");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(response.body()).doesNotContain("!!! not base64 !!!");
    }

    @Test
    @DisplayName("A bridge that shows no accounts is a 409, not an empty success")
    void anEmptyBridgeIsAConflict() {
        bridge.returning(accessUrl(UUID.randomUUID().toString()), List.of());

        HttpResponse<String> response = exchange(token("https://bridge.example.com/claim/d"));

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(Json.errorCode(response.body())).isEqualTo("conflict");
    }

    @Test
    @DisplayName("a URL-safe base64 token is a valid token, not a malformed one")
    void aUrlSafeTokenIsAccepted() {
        String suffix = UUID.randomUUID().toString();
        bridge.returning(accessUrl(suffix), List.of(bridgeAccount("chq-" + suffix, "Chequing",
            "CAD")));
        String claim = "https://bridge.example.com/claim/" + suffix + "?a=b&c=d";
        String urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString(
            claim.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        HttpResponse<String> response = exchange(urlSafe);

        assertThat(response.statusCode())
            .as("bridges differ; a token that is URL-safe base64 is not a malformed token")
            .isEqualTo(201);
    }

    @Test
    @DisplayName("a token that decodes to something that is not a web address is 400")
    void aTokenThatIsNotAUrlIsFourHundred() {
        HttpResponse<String> response = exchange(token("ftp://bridge.example.com/claim/x"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
        assertThat(response.body()).doesNotContain("ftp://bridge.example.com");
    }

    @Test
    @DisplayName("a bridge account with no id of its own is skipped, not connected")
    void anAccountWithNoBridgeIdIsSkipped() {
        String suffix = UUID.randomUUID().toString();
        bridge.returning(accessUrl(suffix), List.of(
            bridgeAccount(null, "Nameless", "CAD"),
            bridgeAccount("chq-" + suffix, "Chequing", "GBP")));

        JsonNode data = Json.data(exchange(token("https://bridge.example.com/claim/e")).body());

        assertThat(Json.elements(data.path("accountIds")))
            .as("an account with no id cannot be looked up again, so connecting it would create a "
                + "duplicate on the next exchange")
            .hasSize(1);
        String accountId = Json.elements(data.path("accountIds")).get(0).asString("");
        assertThat(Json.data(get(appUrl("/v1/accounts/" + accountId),
            admin.accessToken()).body()).path("currency").asString())
            .as("F4: a currency this install does not support falls back to the member's own, "
                + "never to a literal")
            .isEqualTo("CAD");
    }

    @Test
    @DisplayName("only SIMPLEFIN is exchanged with a setup token")
    void anotherSourceKindIsRefused() {
        HttpResponse<String> response = withoutRequestValidation(
            "the contract says sourceKind is SIMPLEFIN; this asserts what the server does with "
                + "the value no generated client can send",
            () -> postJson(appUrl("/v1/accounts/exchange-setup-token"),
                Json.object("sourceKind", "OFX", "setupToken",
                    token("https://bridge.example.com/claim/f")),
                "Authorization", "Bearer " + admin.accessToken()));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(response.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("RD2: a bridge soft error fails the fetch with a plain reason, not a silent success")
    void aBridgeSoftErrorFailsTheFetch() {
        String suffix = UUID.randomUUID().toString();
        bridge.returning(accessUrl(suffix), List.of(bridgeAccount("chq-" + suffix, "Chequing",
            "CAD")));
        bridge.reportingErrors(List.of("Connection to My Bank needs re-authentication, see "
            + "https://bridge.example.com/secret-path?token=abc"));

        // The registry's test double stands in for SimplefinSource (see TestBankSource), so the
        // real source is driven directly; the orchestrator turns this exception into the FAILED
        // run, the account's errorMessage and the SYNC_FAILED notification.
        assertThatThrownBy(() -> simplefinSource.fetch("chq-" + suffix,
            java.time.LocalDate.now().minusDays(30), java.time.LocalDate.now(),
            java.util.Map.of(SimplefinSource.CONFIG_ACCESS_URL, accessUrl(suffix))))
            .isInstanceOf(finance.trove.common.plugin.BankSourceConnectionException.class)
            .hasMessageContaining("re-authentication")
            .message().doesNotContain("http").doesNotContain("secret-path");
    }

    private HttpResponse<String> exchange(String setupToken) {
        return postJson(appUrl("/v1/accounts/exchange-setup-token"),
            Json.object("sourceKind", "SIMPLEFIN", "setupToken", setupToken),
            "Authorization", "Bearer " + admin.accessToken());
    }

    private static String token(String claimUrl) {
        return Base64.getEncoder().encodeToString(claimUrl.getBytes(
            java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String accessUrl(String suffix) {
        return "https://bridge.example.com/simplefin/ACCESS-" + suffix;
    }

    private static SimplefinAccount bridgeAccount(String id, String name, String currency) {
        return new SimplefinAccount(id, name, currency, null, List.of());
    }

    private static List<String> accountIds(HttpResponse<String> response) {
        return accountIds(response.body());
    }

    private static List<String> accountIds(String body) {
        return Json.elements(Json.data(body).path("accountIds")).stream()
            .map(node -> node.asString(""))
            .toList();
    }

    private int accountCount() {
        return Json.elements(Json.data(
            get(appUrl("/v1/accounts"), admin.accessToken()).body())).size();
    }
}
