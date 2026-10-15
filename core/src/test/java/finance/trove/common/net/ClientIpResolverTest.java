package finance.trove.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The one resolver every per-address control in the application shares (gap P2-5).
 *
 * <p>The important assertion in this class is the negative one: {@code X-Forwarded-For} on the
 * request changes nothing. Deciding the address is Tomcat's {@code RemoteIpValve}'s job, from a
 * configured list of proxies; a resolver that read the header itself would reintroduce exactly the
 * spoof the valve exists to close, and would do it in a class whose name suggests it had been
 * thought about.
 */
class ClientIpResolverTest {

    @Test
    @DisplayName("The address is the socket peer, and an X-Forwarded-For header is ignored here")
    void theHeaderIsNeverRead() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "10.0.0.1, 192.168.0.1");

        assertThat(ClientIpResolver.resolve(request))
            .as("the header is the valve's input, never this class's; reading it here is the "
                + "pre-collapse bug (gap P2-5)")
            .isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("No request in scope is null, not a placeholder")
    void noRequestIsNull() {
        assertThat(ClientIpResolver.resolve(null))
            .as("a scheduled job and a console command have no address; an inet column takes a "
                + "null but not the string \"unknown\"")
            .isNull();
    }

    @Test
    @DisplayName("A bracketed IPv6 literal loses its brackets")
    void bracketsAreStripped() {
        assertThat(ClientIpResolver.normalise("[2001:db8::1]")).isEqualTo("2001:db8::1");
    }

    @Test
    @DisplayName("An IPv6 zone index is dropped — it means nothing off the machine that made it")
    void zoneIndexIsDropped() {
        assertThat(ClientIpResolver.normalise("fe80::1%eth0")).isEqualTo("fe80::1");
    }

    @Test
    @DisplayName("Case is normalised, so one address is one bucket")
    void caseIsNormalised() {
        assertThat(ClientIpResolver.normalise("2001:DB8::AB")).isEqualTo("2001:db8::ab");
    }

    @Test
    @DisplayName("Whitespace is trimmed and an empty value becomes null")
    void blankBecomesNull() {
        assertThat(ClientIpResolver.normalise("  203.0.113.7 ")).isEqualTo("203.0.113.7");
        assertThat(ClientIpResolver.normalise("   ")).isNull();
        assertThat(ClientIpResolver.normalise("")).isNull();
        assertThat(ClientIpResolver.normalise(null)).isNull();
    }

    @Test
    @DisplayName("A value too long to be an address is dropped rather than stored")
    void implausiblyLongValuesAreDropped() {
        assertThat(ClientIpResolver.normalise("2001:db8::1".repeat(20)))
            .as("the inet column would reject it and the audit write would be lost; dropping it "
                + "keeps the rest of the row")
            .isNull();
    }
}
