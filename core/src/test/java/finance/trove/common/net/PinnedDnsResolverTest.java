package finance.trove.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PinnedDnsResolverTest {

    private static final String HOST = "api.example.com";

    @Test
    @DisplayName("the pinned host resolves to the approved address and nothing else")
    void resolvesOnlyTheApprovedAddress() throws Exception {
        InetAddress approved = InetAddress.getByName("93.184.216.34");
        PinnedDnsResolver resolver = new PinnedDnsResolver(target(approved));

        assertThat(resolver.resolve(HOST)).containsExactly(approved);
        assertThat(resolver.resolveCanonicalHostname(HOST)).isEqualTo(HOST);
    }

    @Test
    @DisplayName("host matching is case-insensitive, as DNS is")
    void hostMatchingIgnoresCase() throws Exception {
        PinnedDnsResolver resolver =
            new PinnedDnsResolver(target(InetAddress.getByName("93.184.216.34")));

        assertThat(resolver.resolve("API.Example.COM")).hasSize(1);
    }

    @Test
    @DisplayName("any other host — a redirect target, say — cannot be resolved at all")
    void refusesEveryOtherHost() throws Exception {
        PinnedDnsResolver resolver =
            new PinnedDnsResolver(target(InetAddress.getByName("93.184.216.34")));

        assertThatThrownBy(() -> resolver.resolve("169.254.169.254"))
            .isInstanceOf(UnknownHostException.class).hasMessageContaining("pinned");
        assertThatThrownBy(() -> resolver.resolve("evil.example"))
            .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolve(null))
            .isInstanceOf(UnknownHostException.class);
        assertThatThrownBy(() -> resolver.resolveCanonicalHostname("evil.example"))
            .isInstanceOf(UnknownHostException.class);
    }

    @Test
    @DisplayName("the returned array is a copy, so a caller cannot repoint the pin")
    void theAnswerIsDefensivelyCopied() throws Exception {
        InetAddress approved = InetAddress.getByName("93.184.216.34");
        PinnedDnsResolver resolver = new PinnedDnsResolver(target(approved));

        InetAddress[] first = resolver.resolve(HOST);
        first[0] = InetAddress.getByName("127.0.0.1");

        assertThat(resolver.resolve(HOST)).containsExactly(approved);
    }

    @Test
    void resolvedTargetValidatesItsComponents() throws Exception {
        InetAddress approved = InetAddress.getByName("93.184.216.34");
        URI uri = URI.create("https://" + HOST + "/v1");

        assertThatThrownBy(() -> new ResolvedTarget(null, approved, 443, uri, List.of(approved)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResolvedTarget(HOST, null, 443, uri, List.of(approved)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResolvedTarget(HOST, approved, 443, null, List.of(approved)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ResolvedTarget(HOST, approved, 443, uri, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static ResolvedTarget target(InetAddress address) {
        return new ResolvedTarget(HOST, address, 443, URI.create("https://" + HOST + "/v1"),
            List.of(address));
    }
}
