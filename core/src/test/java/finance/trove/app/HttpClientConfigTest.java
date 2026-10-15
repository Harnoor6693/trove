package finance.trove.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import finance.trove.common.net.EgressProperties;
import finance.trove.common.net.OutboundUrlPolicy;
import finance.trove.common.net.ResolvedTarget;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The pinned HTTP client of {@code docs/recovery/06-COLLAPSE-PLAN.md} §5.5, against a real socket.
 *
 * <p>WireMock listens on loopback, which the policy would normally refuse — so the policy is not
 * consulted here. What is under test is the second half of the defence: that a client built for a
 * {@link ResolvedTarget} connects to that target's address, sends the target's hostname in
 * {@code Host}, and cannot be talked into resolving anything else.
 */
class HttpClientConfigTest {

    private WireMockServer server;
    private HttpClientConfig.PinnedRestClientFactory factory;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort()
            .bindAddress("127.0.0.1"));
        server.start();
        factory = new HttpClientConfig(
            new HttpClientProperties(5_000, 30_000), emptyProvider()).pinnedRestClientFactory();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    @DisplayName("the client connects to the pinned IP and sends the target's Host header")
    void connectsToThePinnedAddressWithTheRealHostHeader() throws Exception {
        server.stubFor(get(urlEqualTo("/v1/models"))
            .willReturn(aResponse().withStatus(200).withBody("{\"data\":[]}")));

        String body = factory.clientFor(targetFor("ollama.internal"))
            .get().uri("/v1/models").retrieve().body(String.class);

        assertThat(body).isEqualTo("{\"data\":[]}");
        server.verify(getRequestedFor(urlEqualTo("/v1/models"))
            .withHeader("Host", WireMock.containing("ollama.internal")));
    }

    @Test
    @DisplayName("the User-Agent identifies the build, so an upstream can attribute the traffic")
    void sendsAnIdentifyingUserAgent() throws Exception {
        server.stubFor(get(urlEqualTo("/v1/ping")).willReturn(aResponse().withStatus(204)));

        factory.clientFor(targetFor("ollama.internal"))
            .get().uri("/v1/ping").retrieve().toBodilessEntity();

        server.verify(getRequestedFor(urlEqualTo("/v1/ping"))
            .withHeader("User-Agent", WireMock.containing("trove-core/")));
    }

    @Test
    @DisplayName("a redirect is not followed — it is the cheapest way around a URL allowlist")
    void doesNotFollowRedirects() throws Exception {
        server.stubFor(get(urlEqualTo("/v1/redirect")).willReturn(aResponse().withStatus(302)
            .withHeader("Location", "http://169.254.169.254/latest/meta-data/")));

        var response = factory.clientFor(targetFor("ollama.internal"))
            .get().uri("/v1/redirect").retrieve().toBodilessEntity();

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        server.verify(1, getRequestedFor(urlEqualTo("/v1/redirect")));
    }

    @Test
    @DisplayName("a read timeout is enforced, so a hung endpoint cannot pin a request thread")
    void enforcesTheReadTimeout() throws Exception {
        server.stubFor(get(urlEqualTo("/v1/slow"))
            .willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
        RestClient impatient = factory
            .builderFor(targetFor("ollama.internal"), Duration.ofMillis(500),
                Duration.ofMillis(250))
            .build();

        assertThatThrownBy(() -> impatient.get().uri("/v1/slow").retrieve().toBodilessEntity())
            .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("the pinned client cannot reach a host other than its target, even by absolute URI")
    void refusesAnyOtherHost() throws Exception {
        RestClient pinned = factory.clientFor(targetFor("ollama.internal"));

        assertThatThrownBy(() -> pinned.get()
            .uri(URI.create("http://169.254.169.254/latest/meta-data/"))
            .retrieve().toBodilessEntity())
            .isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("timeout properties fall back to the documented 5 s / 30 s when unset or absurd")
    void timeoutPropertiesHaveSafeDefaults() {
        assertThat(new HttpClientProperties(0, 0).connectTimeout())
            .isEqualTo(Duration.ofMillis(HttpClientProperties.DEFAULT_CONNECT_TIMEOUT_MS));
        assertThat(new HttpClientProperties(-1, -1).readTimeout())
            .isEqualTo(Duration.ofMillis(HttpClientProperties.DEFAULT_READ_TIMEOUT_MS));
        assertThat(new HttpClientProperties(1_000, 2_000).connectTimeout())
            .isEqualTo(Duration.ofSeconds(1));
        assertThat(new HttpClientProperties(1_000, 2_000).readTimeout())
            .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("the global customizer is applied to any injected RestClient.Builder")
    void theGlobalCustomizerConfiguresAnInjectedBuilder() {
        server.stubFor(get(urlEqualTo("/v1/plain")).willReturn(aResponse().withStatus(200)));
        RestClient.Builder builder = RestClient.builder();

        new HttpClientConfig(new HttpClientProperties(5_000, 30_000), emptyProvider())
            .troveRestClientCustomizer().customize(builder);

        builder.build().get().uri(server.baseUrl() + "/v1/plain").retrieve().toBodilessEntity();
        server.verify(getRequestedFor(urlEqualTo("/v1/plain"))
            .withHeader("User-Agent", WireMock.containing("trove-core/")));
    }

    @Test
    @DisplayName("a policy-approved public target produces a usable builder")
    void aPolicyApprovedTargetBuildsAClient() {
        OutboundUrlPolicy policy = new OutboundUrlPolicy(new EgressProperties(false),
            host -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});

        ResolvedTarget approved = policy.check("https://api.example.com/v1");

        assertThat(factory.builderFor(approved).build()).isNotNull();
        assertThat(approved.origin()).isEqualTo("https://api.example.com:443");
    }

    /** A target whose hostname is fictional but whose pinned address is the WireMock socket. */
    private ResolvedTarget targetFor(String host) throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        return new ResolvedTarget(host, loopback, server.port(),
            URI.create("http://" + host + ":" + server.port()), List.of(loopback));
    }

    /**
     * Stands in for a context with no {@code BuildProperties} bean — which is what a test JVM
     * has, and what the User-Agent's {@code unknown} version comes from.
     */
    private static ObjectProvider<BuildProperties> emptyProvider() {
        return new DefaultListableBeanFactory().getBeanProvider(BuildProperties.class);
    }
}
