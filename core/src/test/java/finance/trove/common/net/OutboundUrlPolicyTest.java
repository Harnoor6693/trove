package finance.trove.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SSRF gate ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.5, gap P2-10, §9 risk 9).
 *
 * <p>Every address family the plan names gets a row in the table below, and the rebinding case
 * gets its own test — because "resolves DNS again" is exactly the bug the pinned resolver exists
 * to prevent, and a test that only checks the first lookup would pass against the broken design.
 */
class OutboundUrlPolicyTest {

    private static final String PUBLIC_IPV4 = "93.184.216.34";

    @Nested
    @DisplayName("scheme and syntax")
    class SchemeAndSyntax {

        @Test
        void acceptsHttpsToAPublicAddress() {
            ResolvedTarget target = policy(false, PUBLIC_IPV4)
                .check("https://api.example.com/v1/accounts");

            assertThat(target.host()).isEqualTo("api.example.com");
            assertThat(target.port()).isEqualTo(443);
            assertThat(target.ip().getHostAddress()).isEqualTo(PUBLIC_IPV4);
            assertThat(target.secure()).isTrue();
            assertThat(target.scheme()).isEqualTo("https");
            assertThat(target.origin()).isEqualTo("https://api.example.com:443");
            assertThat(target.allAddresses()).hasSize(1);
        }

        @Test
        void honoursAnExplicitPort() {
            assertThat(policy(false, PUBLIC_IPV4).check("https://api.example.com:8443/x").port())
                .isEqualTo(8443);
        }

        @Test
        @DisplayName("plain http is refused even to a public host unless private targets are on")
        void refusesPlainHttpByDefault() {
            assertThatThrownBy(() -> policy(false, PUBLIC_IPV4).check("http://api.example.com/x"))
                .isInstanceOf(OutboundUrlPolicyException.class)
                .hasMessageContaining("TROVE_EGRESS_ALLOW_PRIVATE");
        }

        @ParameterizedTest(name = "[{0}] is refused")
        @ValueSource(strings = {
            "ftp://example.com/x",
            "file:///etc/passwd",
            "gopher://example.com:70/",
            "jar:https://example.com/a.jar!/",
            "not a url at all",
            "/relative/path",
            "https://",
            " https://example.com/x",
        })
        void refusesEverySchemeAndShapeThatIsNotAnAbsoluteHttpUrl(String url) {
            assertThatThrownBy(() -> policy(true, PUBLIC_IPV4).check(url, true))
                .isInstanceOf(OutboundUrlPolicyException.class);
        }

        @Test
        void refusesNullAndBlank() {
            assertThatThrownBy(() -> policy(false, PUBLIC_IPV4).check(null))
                .isInstanceOf(OutboundUrlPolicyException.class).hasMessageContaining("required");
            assertThatThrownBy(() -> policy(false, PUBLIC_IPV4).check("  "))
                .isInstanceOf(OutboundUrlPolicyException.class);
        }

        @Test
        @DisplayName("https://public.example@10.0.0.1/ reads as public to a human, private to a client")
        void refusesCredentialsBeforeTheHost() {
            assertThatThrownBy(() ->
                policy(false, PUBLIC_IPV4).check("https://public.example@api.example.com/x"))
                .isInstanceOf(OutboundUrlPolicyException.class)
                .hasMessageContaining("credentials");
        }

        @Test
        void refusesAHostThatDoesNotResolve() {
            OutboundUrlPolicy failing = new OutboundUrlPolicy(new EgressProperties(false), host -> {
                throw new UnknownHostException(host);
            });

            assertThatThrownBy(() -> failing.check("https://nowhere.example/x"))
                .isInstanceOf(OutboundUrlPolicyException.class)
                .hasMessageContaining("nowhere.example").hasMessageContaining("must resolve");
        }

        @Test
        void refusesAnEmptyResolverAnswer() {
            OutboundUrlPolicy empty =
                new OutboundUrlPolicy(new EgressProperties(false), host -> new InetAddress[0]);
            OutboundUrlPolicy nullsOnly =
                new OutboundUrlPolicy(new EgressProperties(false), host -> new InetAddress[] {null});

            assertThatThrownBy(() -> empty.check("https://nowhere.example/x"))
                .isInstanceOf(OutboundUrlPolicyException.class);
            assertThatThrownBy(() -> nullsOnly.check("https://nowhere.example/x"))
                .isInstanceOf(OutboundUrlPolicyException.class);
        }
    }

    @Nested
    @DisplayName("blocked address ranges")
    class BlockedRanges {

        @ParameterizedTest(name = "{0} is refused as {1}")
        @CsvSource({
            "127.0.0.1,          loopback",
            "127.1.2.3,          loopback",
            "0.0.0.0,            unspecified",
            "0.10.20.30,         0.0.0.0/8",
            "10.0.0.1,           private",
            "10.255.255.254,     private",
            "172.16.0.1,         private",
            "172.31.255.254,     private",
            "192.168.1.1,        private",
            "169.254.1.1,        link-local",
            "169.254.169.254,    instance-metadata",
            "100.64.0.1,         carrier-grade NAT",
            "100.127.255.254,    carrier-grade NAT",
            "192.0.0.1,          protocol-assignment",
            "198.18.0.1,         benchmarking",
            "198.19.255.254,     benchmarking",
            "240.0.0.1,          reserved",
            "255.255.255.255,    reserved",
            "224.0.0.1,          multicast",
            "::1,                loopback",
            "fe80::1,            link-local",
            "fc00::1,            unique-local",
            "fd00::1,            unique-local",
            "fdff:ffff::1,       unique-local",
            "ff02::1,            multicast",
            "::,                 unspecified",
            "::ffff:127.0.0.1,   loopback",
            "::0.0.0.2,          IPv4-compatible",
            // B3-11: IPv6 transition prefixes carry an IPv4 address the network may route to
            "64:ff9b::7f00:1,                       loopback",
            "64:ff9b::a00:1,                        private",
            "2002:c0a8:101::1,                      private",
            "2002:7f00:1::1,                        loopback",
            "2001:0:4136:e378:8000:63bf:f5ff:fffe,  private",
        })
        @DisplayName("every range gap P2-10 and §9 risk 9 name is blocked, with a reason")
        void everyBlockedRangeIsRefused(String address, String expectedReason)
            throws UnknownHostException {

            String reason = OutboundUrlPolicy.blockReason(InetAddress.getByName(address));

            assertThat(reason)
                .as("%s should be blocked", address)
                .isNotNull()
                .contains(expectedReason);
            assertThatThrownBy(() -> policy(false, address).check("https://evil.example/x"))
                .isInstanceOf(OutboundUrlPolicyException.class)
                .hasMessageContaining("evil.example");
        }

        @Test
        @DisplayName("a true ::ffff:0:0/96 IPv6 address is blocked even though Java rarely makes one")
        void ipv4MappedIpv6IsBlocked() throws UnknownHostException {
            byte[] mapped = new byte[16];
            mapped[10] = (byte) 0xFF;
            mapped[11] = (byte) 0xFF;
            mapped[12] = 8;
            mapped[13] = 8;
            mapped[14] = 8;
            mapped[15] = 8;
            InetAddress address = Inet6Address.getByAddress("mapped.example", mapped, 0);

            assertThat(OutboundUrlPolicy.blockReason(address)).contains("IPv4-mapped");
        }

        @ParameterizedTest(name = "{0} is allowed")
        @ValueSource(strings = {
            "93.184.216.34", "8.8.8.8", "1.1.1.1", "172.32.0.1", "100.63.255.255",
            "100.128.0.1", "192.0.1.1", "198.20.0.1", "2606:2800:220:1:248:1893:25c8:1946",
            "2001:4860:4860::8888", "64:ff9b::808:808", "2002:808:808::1",
        })
        @DisplayName("a genuinely public address on either family is allowed")
        void publicAddressesAreAllowed(String address) throws UnknownHostException {
            assertThat(OutboundUrlPolicy.blockReason(InetAddress.getByName(address))).isNull();
            assertThat(policy(false, address).check("https://good.example/x").host())
                .isEqualTo("good.example");
        }

        @Test
        @DisplayName("one private A record among several public ones fails the whole name")
        void everyResolvedAddressMustPass() {
            OutboundUrlPolicy policy = policy(false, PUBLIC_IPV4, "8.8.8.8", "10.0.0.5");

            assertThatThrownBy(() -> policy.check("https://mixed.example/x"))
                .isInstanceOf(OutboundUrlPolicyException.class)
                .hasMessageContaining("mixed.example");
        }

        @Test
        @DisplayName("B3-10: loopback, private, metadata and unresolvable hosts are refused alike")
        void everyAddressRefusalReadsTheSame() {
            // The refusal goes back to any member. A message that named the range, or said
            // "does not resolve", told them which internal names exist and what class of address
            // each one has: a reconnaissance tool. The detail goes to the server log only.
            OutboundUrlPolicy unresolvable =
                new OutboundUrlPolicy(new EgressProperties(false), host -> {
                    throw new UnknownHostException(host);
                });
            List<String> messages = Stream.of(
                    catchThrowable(() -> policy(false, "127.0.0.1").check("https://h.example/x")),
                    catchThrowable(() -> policy(false, "10.1.2.3").check("https://h.example/x")),
                    catchThrowable(() -> policy(false, "169.254.169.254")
                        .check("https://h.example/x")),
                    catchThrowable(() -> unresolvable.check("https://h.example/x")))
                .map(refusal -> {
                    assertThat(refusal).isInstanceOf(OutboundUrlPolicyException.class);
                    return refusal.getMessage();
                })
                .toList();

            assertThat(messages).as("one generic message for every address refusal")
                .containsOnly(messages.get(0));
            assertThat(messages.get(0)).contains("h.example")
                .doesNotContain("loopback", "private", "metadata", "not resolve");
        }

        @Test
        @DisplayName("the refusal names the rule but never the resolved address")
        void refusalDoesNotLeakTheInternalAddress() {
            assertThatThrownBy(() -> policy(false, "10.1.2.3").check("https://internal.example/x"))
                .isInstanceOf(OutboundUrlPolicyException.class)
                .hasMessageNotContaining("10.1.2.3");
        }

        @Test
        void bracketedIpv6LiteralsAreParsed() {
            assertThatThrownBy(() -> policy(false, "::1").check("https://[::1]:8080/x"))
                .isInstanceOf(OutboundUrlPolicyException.class);
        }
    }

    @Nested
    @DisplayName("the two-key private opt-in (§1.6, owner decision 9)")
    class PrivateOptIn {

        @Test
        @DisplayName("a LAN Ollama is reachable only when the install AND the user both allow it")
        void bothKeysAreRequired() {
            String lanUrl = "http://ollama.lan:11434/v1";

            assertThatThrownBy(() -> policy(false, "192.168.1.50").check(lanUrl, true))
                .as("install says no")
                .isInstanceOf(OutboundUrlPolicyException.class);
            assertThatThrownBy(() -> policy(true, "192.168.1.50").check(lanUrl, false))
                .as("user has not opted in")
                .isInstanceOf(OutboundUrlPolicyException.class);

            ResolvedTarget target = policy(true, "192.168.1.50").check(lanUrl, true);
            assertThat(target.host()).isEqualTo("ollama.lan");
            assertThat(target.port()).isEqualTo(11434);
            assertThat(target.secure()).isFalse();
        }

        @Test
        @DisplayName("the single-argument check never allows a private target, whatever the install")
        void theDefaultCheckIsAlwaysStrict() {
            assertThatThrownBy(() -> policy(true, "127.0.0.1").check("https://localhost.example/x"))
                .isInstanceOf(OutboundUrlPolicyException.class);
        }

        @Test
        void anOptedInCallerStillCannotUseANonHttpScheme() {
            assertThatThrownBy(() -> policy(true, "192.168.1.50").check("file:///etc/passwd", true))
                .isInstanceOf(OutboundUrlPolicyException.class);
        }

        @Test
        @DisplayName("both keys do widen the policy to loopback — a member's own model on this box")
        void bothKeysReachLoopback() {
            assertThat(policy(true, "127.0.0.1").check("http://ollama.local:11434/v1", true).port())
                .isEqualTo(11434);
        }

        @ParameterizedTest(name = "{0} stays refused as {1} even with BOTH keys set")
        @CsvSource({
            "169.254.169.254,   instance-metadata",
            "169.254.1.1,       link-local",
            "fe80::1,           link-local",
            "64:ff9b::a9fe:a9fe, instance-metadata",
            "2002:a9fe:a9fe::1, instance-metadata",
            "::a9fe:a9fe,       instance-metadata",
            "::169.254.1.1,     link-local",
            "::6464:64c8,       instance-metadata",
            "::0.1.2.3,         0.0.0.0/8",
            "::e000:1,          multicast",
            "100.100.100.200,   instance-metadata",
            "fd00:ec2::254,     instance-metadata",
            "0.0.0.0,           unspecified",
            "::,                unspecified",
            "0.1.2.3,           0.0.0.0/8",
            "224.0.0.1,         multicast",
            "239.255.255.250,   multicast",
            "ff02::1,           multicast",
            "240.0.0.1,         reserved",
            "255.255.255.255,   reserved",
            "::ffff:0.0.0.0,    unspecified",
        })
        @DisplayName("the opt-in widens to LAN and loopback, never to link-local, metadata, "
            + "unspecified, multicast or reserved ranges")
        void theOptInNeverReachesLinkLocal(String address, String expectedReason)
            throws UnknownHostException {
            assertThat(OutboundUrlPolicy.unconditionalBlockReason(InetAddress.getByName(address)))
                .contains(expectedReason);
            assertThatThrownBy(() ->
                policy(true, address).check("http://ollama.lan:11434/v1", true))
                .as("TROVE_EGRESS_ALLOW_PRIVATE plus a member's own opt-in must not be a way to "
                    + "read this host's cloud credentials")
                .isInstanceOf(OutboundUrlPolicyException.class)
                .hasMessageContaining("ollama.lan");
        }

        @ParameterizedTest(name = "{0} is a private target the opt-in does lift")
        @ValueSource(strings = {"10.1.2.3", "192.168.1.50", "127.0.0.1", "::1", "100.64.0.1",
            "100.100.100.199", "fd12:3456::1", "fd00:ec2::253"})
        @DisplayName("RFC 1918, loopback, CGNAT and ULA stay reachable with both keys")
        void theOptInStillLiftsPrivateRanges(String address) {
            assertThat(policy(true, address).check("http://ollama.lan:11434/v1", true).port())
                .isEqualTo(11434);
        }

        @Test
        @DisplayName("an IPv4-compatible ::a9fe:a9fe cannot slip past the always-blocked set via the opt-in")
        void anIpv4CompatibleMetadataAddressIsRefusedEndToEndWithBothKeys() {
            assertThatThrownBy(() -> policy(true, "::a9fe:a9fe")
                .check("http://ollama.lan:11434/v1", true))
                .isInstanceOf(OutboundUrlPolicyException.class);
        }

        @Test
        @DisplayName("::1 is still plain IPv6 loopback, which the opt-in lifts")
        void ipv6LoopbackIsNotMistakenForAnEmbeddedIpv4() {
            assertThat(policy(true, "::1").check("http://ollama.lan:11434/v1", true).port())
                .isEqualTo(11434);
        }

        @Test
        @DisplayName("::ffff:169.254.169.254 is the same destination written differently")
        void anIpv4MappedMetadataAddressIsBlockedTooWithBothKeys() throws UnknownHostException {
            byte[] mapped = new byte[16];
            mapped[10] = (byte) 0xFF;
            mapped[11] = (byte) 0xFF;
            mapped[12] = (byte) 169;
            mapped[13] = (byte) 254;
            mapped[14] = (byte) 169;
            mapped[15] = (byte) 254;
            InetAddress address = Inet6Address.getByAddress("metadata.example", mapped, 0);

            assertThat(OutboundUrlPolicy.unconditionalBlockReason(address))
                .as("Inet6Address#isLinkLocalAddress answers false for a v4-mapped address")
                .contains("instance-metadata");
        }
    }

    @Nested
    @DisplayName("DNS rebinding")
    class Rebinding {

        @Test
        @DisplayName("a resolver that answers public then private cannot slip a request through")
        void theSecondAnswerIsNeverUsed() throws Exception {
            Deque<InetAddress[]> answers = new ArrayDeque<>(List.of(
                new InetAddress[] {InetAddress.getByName(PUBLIC_IPV4)},
                new InetAddress[] {InetAddress.getByName("169.254.169.254")}));
            OutboundUrlPolicy policy =
                new OutboundUrlPolicy(new EgressProperties(false), host -> answers.poll());

            ResolvedTarget target = policy.check("https://rebind.example/v1");

            // The check passed on the first answer. What matters is that the client is now
            // pinned to that answer and never asks the resolver again — the second, hostile
            // answer is unreachable through the pinned resolver.
            assertThat(target.ip().getHostAddress()).isEqualTo(PUBLIC_IPV4);

            PinnedDnsResolver pinned = new PinnedDnsResolver(target);
            assertThat(pinned.resolve("rebind.example"))
                .containsExactly(InetAddress.getByName(PUBLIC_IPV4));
            assertThat(answers).as("the pinned resolver consumed no further DNS answer").hasSize(1);
        }
    }

    private static OutboundUrlPolicy policy(boolean allowPrivate, String... addresses) {
        return new OutboundUrlPolicy(new EgressProperties(allowPrivate), host -> {
            InetAddress[] resolved = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                resolved[i] = InetAddress.getByName(addresses[i]);
            }
            return resolved;
        });
    }
}
