package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Who may read {@code /actuator/prometheus} (§5.1, §5.9).
 *
 * <p>{@code SecurityHeadersIT} proves the network half over HTTP. The token half cannot be proved
 * there: the shared integration context has no token configured, and configuring one would mean a
 * second Spring context, which {@code BaseIT}'s contract forbids. So the decision is a static
 * method and both halves are exercised here.
 */
class ManagementAccessTest {

    private static final ManagementAccessProperties NETWORKS_ONLY =
        new ManagementAccessProperties(List.of("172.16.0.0/12", "127.0.0.0/8"), null);

    private static final ManagementAccessProperties WITH_TOKEN =
        new ManagementAccessProperties(List.of("172.16.0.0/12"), "a-scrape-token");

    private static MockHttpServletRequest from(String address, String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.setRemoteAddr(address);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    @Test
    @DisplayName("A peer inside the compose network may scrape with no credential at all")
    void theComposeNetworkNeedsNoCredential() {
        assertThat(SecurityConfig.mayReadMetrics(from("172.18.0.4", null), NETWORKS_ONLY))
            .as("the Prometheus sidecar under the monitoring profile needs no configuration")
            .isTrue();
        assertThat(SecurityConfig.mayReadMetrics(from("127.0.0.1", null), NETWORKS_ONLY)).isTrue();
    }

    @Test
    @DisplayName("A peer outside it is refused")
    void outsideTheNetworkIsRefused() {
        assertThat(SecurityConfig.mayReadMetrics(from("203.0.113.55", null), NETWORKS_ONLY))
            .as("request counts, timings and every meter tag are reconnaissance, not a liveness "
                + "answer")
            .isFalse();
    }

    @Test
    @DisplayName("The token opens the door from outside, and only the right token does")
    void theTokenIsTheOtherWayIn() {
        assertThat(SecurityConfig.mayReadMetrics(
            from("203.0.113.55", "Bearer a-scrape-token"), WITH_TOKEN)).isTrue();
        assertThat(SecurityConfig.mayReadMetrics(
            from("203.0.113.55", "Bearer a-scrape-toke"), WITH_TOKEN))
            .as("compared with MessageDigest.isEqual, so a near miss costs the same as a wild one")
            .isFalse();
        assertThat(SecurityConfig.mayReadMetrics(
            from("203.0.113.55", "a-scrape-token"), WITH_TOKEN))
            .as("the scheme is part of the credential")
            .isFalse();
        assertThat(SecurityConfig.mayReadMetrics(from("203.0.113.55", null), WITH_TOKEN))
            .isFalse();
    }

    @Test
    @DisplayName("With no token configured the header route is CLOSED, not open")
    void aBlankTokenClosesTheHeaderRoute() {
        assertThat(SecurityConfig.mayReadMetrics(from("203.0.113.55", "Bearer "), NETWORKS_ONLY))
            .as("an empty configured secret matching an empty presented one is how an optional "
                + "credential becomes no credential")
            .isFalse();
        assertThat(SecurityConfig.mayReadMetrics(from("203.0.113.55", "Bearer  "), NETWORKS_ONLY))
            .isFalse();
    }

    @Test
    @DisplayName("An address the matcher cannot parse is not a member of any network")
    void anUnparseableAddressIsRefused() {
        MockHttpServletRequest request = from("not-an-address", null);
        assertThat(SecurityConfig.mayReadMetrics(request, NETWORKS_ONLY)).isFalse();

        MockHttpServletRequest none = from(null, null);
        assertThat(SecurityConfig.mayReadMetrics(none, NETWORKS_ONLY))
            .as("no resolvable address is not an argument for letting the request through")
            .isFalse();
    }

    @Test
    @DisplayName("An IPv4 literal against an IPv6 block is a miss, not an error")
    void mixedFamiliesDoNotThrow() {
        ManagementAccessProperties mixed =
            new ManagementAccessProperties(List.of("::1/128", "10.0.0.0/8"), null);

        assertThat(SecurityConfig.mayReadMetrics(from("10.1.2.3", null), mixed))
            .as("the first matcher cannot parse an IPv4 literal; the loop must keep going rather "
                + "than fail the whole decision")
            .isTrue();
    }
}
