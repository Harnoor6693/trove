package finance.trove.ingestion.simplefin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import finance.trove.app.HttpClientConfig;
import finance.trove.app.HttpClientProperties;
import finance.trove.app.support.LogCapture;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.net.EgressProperties;
import finance.trove.common.net.HostResolver;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.OutboundUrlPolicyException;
import finance.trove.common.net.ResolvedTarget;
import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.ingestion.simplefin.dto.SimplefinAccountSet;
import java.net.InetAddress;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SimpleFIN bridge client against a real socket, and the two secrets it must never let out.
 *
 * <p>The setup token and the access URL are credentials — a bridge puts its secret in the access
 * URL's path, its userinfo, or both. Every test here that exercises a failure asserts that neither
 * appears in any log line the application wrote or in any exception message, because a leak is
 * indistinguishable from handing the credential to whoever reads the log.
 */
class SimplefinBridgeClientTest {

    /** The path the claim URL points at; its last segment is the one-time credential. */
    private static final String CLAIM_PATH = "/simplefin/claim/SECRET-SETUP-TOKEN-VALUE";

    /** The access URL's credentials, which the bridge embeds in the path it hands back. */
    private static final String ACCESS_SECRET = "SECRET-ACCESS-CREDENTIALS";

    /** The HTTP Basic user a real SimpleFIN access URL carries in its userinfo component. */
    private static final String BASIC_USER = "SECRET-BASIC-USER";

    /** The HTTP Basic password of that same access URL. */
    private static final String BASIC_PASSWORD = "SECRET-BASIC-PASSWORD";

    private static final String HOST = "bridge.simplefin.test";

    private WireMockServer server;
    private SimplefinBridgeClient client;

    @BeforeEach
    void startBridge() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort()
            .bindAddress("127.0.0.1"));
        server.start();
        client = new SimplefinBridgeClient(loopbackPolicy(), factory(),
            SimplefinProperties.defaults(), mapper());
    }

    @AfterEach
    void stopBridge() {
        server.stop();
    }

    @Test
    @DisplayName("the exchange is an empty POST and the response body IS the access URL")
    void claimsTheAccessUrl() {
        server.stubFor(post(urlEqualTo(CLAIM_PATH))
            .willReturn(aResponse().withStatus(200).withBody(accessUrl() + "\n")));

        String accessUrl = client.claimAccessUrl(claimUrl());

        assertThat(accessUrl).isEqualTo(accessUrl());
        server.verify(postRequestedFor(urlEqualTo(CLAIM_PATH))
            .withHeader("Host", WireMock.containing(HOST)));
    }

    @Test
    @DisplayName("403 and 410 mean the token has already been spent — a 409, not a 500")
    void aSpentTokenIsAConflict() {
        server.stubFor(post(urlEqualTo(CLAIM_PATH))
            .willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> client.claimAccessUrl(claimUrl()))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already been used")
            .hasMessageNotContaining("SECRET-SETUP-TOKEN-VALUE");
    }

    @Test
    @DisplayName("the accounts call sends the window as start-date instead of reading everything")
    void sendsTheWindowToTheBridge() {
        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"accounts\":[{\"id\":\"a1\",\"name\":\"Chequing\","
                    + "\"currency\":\"CAD\",\"transactions\":[]}]}")));

        SimplefinAccountSet set = client.fetchAccounts(accessUrl(), LocalDate.of(2026, 6, 1));

        assertThat(set.accountsOrEmpty()).extracting("id").containsExactly("a1");
        server.verify(getRequestedFor(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .withQueryParam("start-date", WireMock.equalTo("1780272000")));
    }

    @Test
    @DisplayName("an answer larger than the cap fails the call rather than filling the heap")
    void capsTheResponse() {
        SimplefinBridgeClient tiny = new SimplefinBridgeClient(loopbackPolicy(), factory(),
            new SimplefinProperties(5_000, 30_000, 64), mapper());
        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(200).withBody("{\"accounts\":[]}"
                + " ".repeat(4096))));

        assertThatThrownBy(() -> tiny.fetchAccounts(accessUrl(), LocalDate.of(2026, 6, 1)))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("larger than");
    }

    @Test
    @DisplayName("neither secret reaches a log line or an exception message when a call fails")
    void neitherSecretEverEscapes() {
        server.stubFor(post(urlEqualTo(CLAIM_PATH)).willReturn(aResponse().withStatus(500)));
        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(401)));

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> client.claimAccessUrl(claimUrl()))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessageNotContaining("SECRET-SETUP-TOKEN-VALUE");
            assertThatThrownBy(() -> client.fetchAccounts(accessUrl(), LocalDate.of(2026, 6, 1)))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessageNotContaining(ACCESS_SECRET);

            assertThat(logs.lines())
                .as("a credential in a log file is a credential handed to whoever reads the log")
                .noneMatch(line -> line.contains("SECRET-SETUP-TOKEN-VALUE")
                    || line.contains(ACCESS_SECRET));
        }
    }

    @Test
    @DisplayName("a transport failure logs a sanitised reason and keeps no cause that quotes the URI")
    void aTransportFailureIsSanitised() {
        // Captured before the socket goes away: the URL is derived from the server's port.
        String claimUrl = claimUrl();
        server.stop();

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> client.claimAccessUrl(claimUrl))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessage("The SimpleFIN bridge could not be reached.")
                .hasNoCause();

            assertThat(logs.lines())
                .noneMatch(line -> line.contains("SECRET-SETUP-TOKEN-VALUE"));
        }
    }

    @Test
    @DisplayName("G2: the egress policy refuses http, private, link-local and a rebinding name")
    void theEgressPolicyGovernsEverySimplefinCall() {
        assertRefused(publicPolicy("10.0.0.7"), "https://" + HOST + CLAIM_PATH,
            "private (RFC 1918)");
        assertRefused(publicPolicy("169.254.169.254"), "https://" + HOST + CLAIM_PATH,
            "cloud instance-metadata");
        assertRefused(publicPolicy("127.0.0.1"), "https://" + HOST + CLAIM_PATH, "loopback");
        assertRefused(publicPolicy("93.184.216.34"), "http://" + HOST + CLAIM_PATH,
            "Plain http is only allowed");
        assertRefused(rebinding("93.184.216.34", "169.254.169.254"),
            "https://" + HOST + CLAIM_PATH, "cloud instance-metadata");
    }

    @Test
    @DisplayName("an egress refusal names the rule and never echoes the token or the URL")
    void anEgressRefusalNeverEchoesTheUrl() {
        SimplefinBridgeClient refusing = new SimplefinBridgeClient(publicPolicy("10.0.0.7"),
            factory(), SimplefinProperties.defaults(), mapper());

        // The one OutboundUrlPolicy message that echoes what it was given — and for this caller
        // what it was given IS the credential the setup token decoded to.
        assertThatThrownBy(() -> refusing.claimAccessUrl(CLAIM_PATH))
            .isInstanceOf(OutboundUrlPolicyException.class)
            .hasMessageContaining("must be absolute")
            .hasMessageNotContaining("SECRET-SETUP-TOKEN-VALUE")
            .hasMessageContaining(SimplefinBridgeClient.REDACTED);
    }

    @Test
    @DisplayName("redaction removes the secret from a message and copes with nothing to remove")
    void redactionHandlesEveryShape() {
        assertThat(SimplefinBridgeClient.redact("connect to https://x/SECRET failed",
            "https://x/SECRET"))
            .isEqualTo("connect to " + SimplefinBridgeClient.REDACTED + " failed");
        assertThat(SimplefinBridgeClient.redact(null, "https://x/SECRET")).isEmpty();
        assertThat(SimplefinBridgeClient.redact("nothing to hide", null))
            .isEqualTo("nothing to hide");
        assertThat(SimplefinBridgeClient.redact("nothing to hide", "   "))
            .isEqualTo("nothing to hide");
        assertThat(SimplefinBridgeClient.redact("connect to  https://x/SECRET  failed",
            "  https://x/SECRET  "))
            .as("an HTTP client quotes the URI without the whitespace the caller sent, so both "
                + "spellings have to go")
            .contains(SimplefinBridgeClient.REDACTED)
            .doesNotContain("SECRET");
    }

    @Test
    @DisplayName("an empty claim response is a failure, not an empty access URL stored as one")
    void anEmptyClaimResponseIsAFailure() {
        server.stubFor(post(urlEqualTo(CLAIM_PATH))
            .willReturn(aResponse().withStatus(200).withBody("   ")));

        assertThatThrownBy(() -> client.claimAccessUrl(claimUrl()))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("returned no access URL");
    }

    @Test
    @DisplayName("410 is the other already-spent answer, and a 4xx that is neither is a failure")
    void theOtherClaimStatuses() {
        server.stubFor(post(urlEqualTo(CLAIM_PATH)).willReturn(aResponse().withStatus(410)));
        assertThatThrownBy(() -> client.claimAccessUrl(claimUrl()))
            .isInstanceOf(ConflictException.class);

        server.stubFor(post(urlEqualTo(CLAIM_PATH)).willReturn(aResponse().withStatus(404)));
        assertThatThrownBy(() -> client.claimAccessUrl(claimUrl()))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("HTTP 404");
    }

    @Test
    @DisplayName("the bridge's own refusal of the credentials is separated from any other status")
    void theAccountsStatuses() {
        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(403)));
        assertThatThrownBy(() -> client.fetchAccounts(accessUrl(), LocalDate.of(2026, 6, 1)))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("refused these credentials");

        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> client.fetchAccounts(accessUrl(), LocalDate.of(2026, 6, 1)))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("HTTP 500");

        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(200).withBody("this is not json")));
        assertThatThrownBy(() -> client.fetchAccounts(accessUrl(), LocalDate.of(2026, 6, 1)))
            .isInstanceOf(BankSourceConnectionException.class)
            .hasMessageContaining("not the account list");
    }

    @Test
    @DisplayName("a trailing slash on the access URL does not become a double slash")
    void aTrailingSlashIsNormalised() {
        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"accounts\":[]}")));

        assertThat(client.fetchAccounts(accessUrl() + "/", LocalDate.of(2026, 6, 1))
            .accountsOrEmpty()).isEmpty();
    }

    @Test
    @DisplayName("a body with no accounts key at all still answers an empty list")
    void anAnswerWithNoAccountsKey() {
        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{}")));

        assertThat(client.fetchAccounts(accessUrl(), LocalDate.of(2026, 6, 1)).accountsOrEmpty())
            .isEmpty();
    }

    @Test
    @DisplayName("a real access URL carries HTTP Basic credentials in its userinfo, and they are "
        + "sent as an Authorization header rather than refused by the egress policy")
    void theUserinfoCredentialIsMovedOntoTheAuthorizationHeader() {
        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"accounts\":[{\"id\":\"a1\",\"name\":\"Chequing\","
                    + "\"currency\":\"CAD\",\"transactions\":[]}]}")));

        SimplefinAccountSet set =
            client.fetchAccounts(credentialedAccessUrl(), LocalDate.of(2026, 6, 1));

        assertThat(set.accountsOrEmpty())
            .as("without the split, OutboundUrlPolicy refuses the userinfo and this call never "
                + "reaches the bridge at all")
            .extracting("id").containsExactly("a1");
        server.verify(getRequestedFor(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .withHeader("Authorization", WireMock.equalTo(expectedBasicHeader())));
    }

    @Test
    @DisplayName("a claim URL with userinfo is claimed the same way")
    void theClaimUrlMayAlsoCarryCredentials() {
        server.stubFor(post(urlEqualTo(CLAIM_PATH))
            .willReturn(aResponse().withStatus(200).withBody(credentialedAccessUrl())));

        assertThat(client.claimAccessUrl(credentialedClaimUrl()))
            .isEqualTo(credentialedAccessUrl());
        server.verify(postRequestedFor(urlEqualTo(CLAIM_PATH))
            .withHeader("Authorization", WireMock.equalTo(expectedBasicHeader())));
    }

    @Test
    @DisplayName("the Basic credential reaches no log line and no exception message")
    void theUserinfoCredentialNeverEscapes() {
        server.stubFor(get(urlPathEqualTo("/simplefin/" + ACCESS_SECRET + "/accounts"))
            .willReturn(aResponse().withStatus(401)));

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() ->
                client.fetchAccounts(credentialedAccessUrl(), LocalDate.of(2026, 6, 1)))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessageNotContaining(BASIC_USER)
                .hasMessageNotContaining(BASIC_PASSWORD);

            String unreachable = credentialedAccessUrl();
            server.stop();
            assertThatThrownBy(() -> client.fetchAccounts(unreachable, LocalDate.of(2026, 6, 1)))
                .isInstanceOf(BankSourceConnectionException.class)
                .hasMessageNotContaining(BASIC_USER)
                .hasMessageNotContaining(BASIC_PASSWORD);

            assertThat(logs.lines())
                .as("the userinfo IS the bridge password; a log line quoting it hands it to "
                    + "whoever reads the log")
                .noneMatch(line -> line.contains(BASIC_USER) || line.contains(BASIC_PASSWORD)
                    || line.contains(ACCESS_SECRET));
        }
    }

    @Test
    @DisplayName("splitting hands back anything it cannot confidently take apart, unchanged")
    void splittingIsConservative() {
        assertThat(SimplefinBridgeClient.split(null))
            .isEqualTo(new SimplefinBridgeClient.Credentialed(null, null));
        assertThat(SimplefinBridgeClient.split("https://bridge.test/simplefin"))
            .isEqualTo(new SimplefinBridgeClient.Credentialed("https://bridge.test/simplefin",
                null));
        assertThat(SimplefinBridgeClient.split("not a url at all"))
            .as("a string that does not parse is the policy's refusal to give, not the split's")
            .isEqualTo(new SimplefinBridgeClient.Credentialed("not a url at all", null));
        assertThat(SimplefinBridgeClient.split("mailto:someone@example.test"))
            .isEqualTo(new SimplefinBridgeClient.Credentialed("mailto:someone@example.test",
                null));
        assertThat(SimplefinBridgeClient.split("  https://u:p@bridge.test/simplefin  "))
            .as("the policy refuses a URL with surrounding whitespace, and tidying it up here "
                + "would accept one it would otherwise have refused")
            .isEqualTo(new SimplefinBridgeClient.Credentialed(
                "  https://u:p@bridge.test/simplefin  ", null));
        assertThat(SimplefinBridgeClient.split("https://u%3Aa:p%40b@bridge.test/simplefin%2Fx"))
            .as("the raw form is cut out of the string, so a percent-encoded path survives and "
                + "the credential comes back decoded")
            .isEqualTo(new SimplefinBridgeClient.Credentialed(
                "https://bridge.test/simplefin%2Fx", "u:a:p@b"));
    }

    private String credentialedClaimUrl() {
        return "http://" + BASIC_USER + ":" + BASIC_PASSWORD + "@" + HOST + ":" + server.port()
            + CLAIM_PATH;
    }

    private String credentialedAccessUrl() {
        return "http://" + BASIC_USER + ":" + BASIC_PASSWORD + "@" + HOST + ":" + server.port()
            + "/simplefin/" + ACCESS_SECRET;
    }

    private static String expectedBasicHeader() {
        return "Basic " + java.util.Base64.getEncoder().encodeToString(
            (BASIC_USER + ":" + BASIC_PASSWORD).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void assertRefused(OutboundUrlPolicy policy, String url, String expectedRule) {
        SimplefinBridgeClient refusing = new SimplefinBridgeClient(policy, factory(),
            SimplefinProperties.defaults(), mapper());

        assertThatThrownBy(() -> refusing.claimAccessUrl(url))
            .isInstanceOf(OutboundUrlPolicyException.class)
            .hasMessageContaining(expectedRule)
            .hasMessageNotContaining("SECRET-SETUP-TOKEN-VALUE");
    }

    private String claimUrl() {
        return "http://" + HOST + ":" + server.port() + CLAIM_PATH;
    }

    private String accessUrl() {
        return "http://" + HOST + ":" + server.port() + "/simplefin/" + ACCESS_SECRET;
    }

    /**
     * A policy that lets the client reach WireMock on loopback.
     *
     * <p>Loopback is exactly what the real policy refuses, so the two-key private opt-in is used
     * here rather than switching the check off: what is under test is the CLIENT, and the policy
     * itself has its own table-driven suite.
     */
    private static OutboundUrlPolicy loopbackPolicy() {
        InetAddress loopback = address("127.0.0.1");
        return new OutboundUrlPolicy(new EgressProperties(true),
            host -> new InetAddress[] {loopback}) {
            @Override
            public ResolvedTarget check(String url) {
                return check(url, true);
            }
        };
    }

    private static OutboundUrlPolicy publicPolicy(String resolvesTo) {
        return new OutboundUrlPolicy(new EgressProperties(false),
            host -> new InetAddress[] {address(resolvesTo)});
    }

    /** A nameserver that answers a public address first and a metadata address second. */
    private static OutboundUrlPolicy rebinding(String first, String second) {
        return new OutboundUrlPolicy(new EgressProperties(false),
            (HostResolver) host -> new InetAddress[] {address(first), address(second)});
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpClientConfig.PinnedRestClientFactory factory() {
        return new HttpClientConfig(new HttpClientProperties(5_000, 30_000), emptyProvider())
            .pinnedRestClientFactory();
    }

    /** A mapper with the application's own settings; the DTOs are plain records. */
    private static ObjectMapper mapper() {
        return JsonMapper.builder().build();
    }

    private static ObjectProvider<BuildProperties> emptyProvider() {
        return new DefaultListableBeanFactory().getBeanProvider(BuildProperties.class);
    }
}
