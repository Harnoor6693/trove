package finance.trove.common.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The one validator every outbound HTTP call goes through — the per-user AI endpoint, SimpleFIN,
 * and webhook deliveries ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.5/§6, gap P2-10, §9
 * risk 9).
 *
 * <p>It replaces three unrelated checks: {@code SafeWebhookUrlValidator} (https plus four
 * {@code InetAddress} predicates, in the notification service only), SimpleFIN's
 * {@code decodedUrl.startsWith("http")}, and the AI endpoint's complete absence of validation.
 * Making the AI base URL user-supplied is what turns a config knob into a standing SSRF
 * primitive, so one validator has to govern all three.
 *
 * <p>What it adds over the source validator:
 * <ul>
 *   <li><strong>Every resolved address must pass, not just the first.</strong> A name with one
 *       public A record and one RFC1918 A record was accepted by anything that checked
 *       {@code getAllByName()[0]}.</li>
 *   <li><strong>The ranges the source missed</strong>: CGNAT {@code 100.64.0.0/10}, IPv6 ULA
 *       {@code fc00::/7}, {@code 0.0.0.0/8}, IPv4-mapped IPv6 {@code ::ffff:0:0/96} (the classic
 *       way to smuggle {@code 127.0.0.1} past an IPv4-only check), the benchmark and reserved
 *       ranges, and {@code 169.254.169.254} by name because it is the payload the whole class of
 *       attack is after.</li>
 *   <li><strong>A pinned result.</strong> {@link #check} returns a {@link ResolvedTarget}, and
 *       {@code HttpClientConfig} connects to the address in it rather than re-resolving. Without
 *       that, validation and connection are two lookups and the second one can differ.</li>
 *   <li><strong>A two-key private-target opt-in.</strong> Private targets need both the
 *       install-wide {@code TROVE_EGRESS_ALLOW_PRIVATE} and the caller's own opt-in, because a
 *       LAN Ollama is the primary AI story (so a blanket block is wrong) but every member
 *       configures their own endpoint (so an instance-wide switch is too coarse) — §1.6, §6,
 *       owner decision 9. With both keys set the policy lifts the private-network blocks only:
 *       RFC 1918, loopback, CGNAT and ULA. Link-local (including {@code 169.254.169.254}), the
 *       Alibaba and AWS-IPv6 metadata addresses, and the unspecified, multicast and reserved
 *       ranges stay refused whatever the keys say; see {@link #unconditionalBlockReason}.</li>
 * </ul>
 *
 * <p>Userinfo in the URL is refused outright: {@code https://evil.example@10.0.0.1/} reads as a
 * public host to a human and to a naive {@code String} check, and as {@code 10.0.0.1} to every
 * HTTP client.
 */
@Component
public class OutboundUrlPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(OutboundUrlPolicy.class);

    /** The cloud metadata endpoint; inside link-local, but named because it is the objective. */
    static final String METADATA_IPV4 = "169.254.169.254";

    private final EgressProperties properties;
    private final HostResolver resolver;

    @Autowired
    public OutboundUrlPolicy(EgressProperties properties) {
        this(properties, HostResolver.SYSTEM);
    }

    /** Test seam: {@code resolver} is how the rebinding case is exercised. */
    public OutboundUrlPolicy(EgressProperties properties, HostResolver resolver) {
        this.properties = properties;
        this.resolver = resolver;
    }

    /**
     * Validates a URL under the default policy: https only, no private destination.
     *
     * @throws OutboundUrlPolicyException if the URL is malformed or the destination is refused
     */
    public ResolvedTarget check(String url) {
        return check(url, false);
    }

    /**
     * Validates a URL, optionally allowing a private destination.
     *
     * @param url            the user-supplied URL
     * @param callerOptedIn  the caller's own opt-in (a user's {@code allow_private_endpoint}).
     *                       Private targets are permitted only when this is true <em>and</em>
     *                       {@code TROVE_EGRESS_ALLOW_PRIVATE} is set on the install
     * @return the approved destination with its pinned address
     */
    public ResolvedTarget check(String url, boolean callerOptedIn) {
        boolean allowPrivate = callerOptedIn && properties.allowPrivate();
        URI uri = parse(url);
        String scheme = uri.getScheme() == null
            ? null : uri.getScheme().toLowerCase(Locale.ROOT);

        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new OutboundUrlPolicyException(
                "Only http and https URLs are allowed, not '" + uri.getScheme() + "'");
        }
        if ("http".equals(scheme) && !allowPrivate) {
            throw new OutboundUrlPolicyException(
                "Plain http is only allowed for a private LAN target you have explicitly opted "
                    + "in, and only when this server allows private targets at all "
                    + "(TROVE_EGRESS_ALLOW_PRIVATE)");
        }
        if (uri.getUserInfo() != null) {
            throw new OutboundUrlPolicyException(
                "The URL must not contain credentials before the host");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new OutboundUrlPolicyException("The URL has no host");
        }
        host = stripBrackets(host);

        int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        if (port < 1 || port > 65535) {
            throw new OutboundUrlPolicyException("Port " + port + " is out of range");
        }

        List<InetAddress> addresses = resolve(host);
        // The unconditional half runs FIRST and runs whatever the two keys say. Widening the
        // policy for a LAN Ollama must not also open the metadata service: 169.254.169.254 is the
        // payload this class exists to keep away from, and an operator switching on
        // TROVE_EGRESS_ALLOW_PRIVATE is not consent to hand every authenticated member a
        // reachability oracle over the host's own credentials.
        for (InetAddress address : addresses) {
            String reason = unconditionalBlockReason(address);
            if (reason != null) {
                throw refusal(host, reason);
            }
        }
        if (!allowPrivate) {
            for (InetAddress address : addresses) {
                String reason = blockReason(address);
                if (reason != null) {
                    throw refusal(host, reason);
                }
            }
        }
        return new ResolvedTarget(host, addresses.get(0), port, uri, addresses);
    }

    private static OutboundUrlPolicyException refusal(String host, String reason) {
        return refusal(host, reason, null);
    }

    /**
     * B3-10: one message for every address refusal, whatever the reason. The caller is any
     * member, and a message naming the range, or saying the name does not resolve, let them map
     * which internal names exist and what each one points at. The reason goes to the log only.
     */
    private static OutboundUrlPolicyException refusal(String host, String reason,
                                                      Throwable cause) {
        LOG.warn("Refused outbound URL for host {}: {}", host, reason);
        return new OutboundUrlPolicyException("The host '" + host + "' was refused: it must "
            + "resolve, and only to addresses this server is allowed to connect to", cause);
    }

    /**
     * Why {@code address} may not be contacted <em>even by an opted-in caller on an install that
     * allows private targets</em>, or {@code null} if the two-key opt-in may reach it.
     *
     * <p>What the opt-in cannot lift: link-local (IPv4 {@code 169.254.0.0/16}, IPv6
     * {@code fe80::/10}), which holds {@code 169.254.169.254}, the cloud instance-metadata service
     * whose answer is the host's own credentials; the two metadata addresses that sit inside
     * ranges the opt-in does lift, Alibaba's {@code 100.100.100.200} (in CGNAT) and AWS's IPv6
     * {@code fd00:ec2::254} (in ULA); and the ranges that are not private networks at all, which no
     * LAN model lives on: unspecified ({@code 0.0.0.0/8}, {@code ::}), multicast
     * ({@code 224.0.0.0/4}, {@code ff00::/8}) and reserved ({@code 240.0.0.0/4}). The opt-in
     * exists so a member can point at a LAN model on RFC 1918, loopback, CGNAT or ULA; treating
     * the rest as one setting is what would turn a per-user endpoint into an SSRF primitive
     * against the install itself, with no administrator anywhere in the path, since every member
     * configures their own endpoint.
     *
     * <p>An IPv4-mapped IPv6 address is unwrapped before the check, because
     * {@code ::ffff:169.254.169.254} is the same destination written differently and
     * {@code Inet6Address#isLinkLocalAddress} answers false for it.
     */
    static String unconditionalBlockReason(InetAddress address) {
        byte[] ipv4 = embeddedIpv4(address);
        if (ipv4 != null) {
            int first = ipv4[0] & 0xFF;
            int second = ipv4[1] & 0xFF;
            if (first == 169 && second == 254) {
                return (ipv4[2] & 0xFF) == 169 && (ipv4[3] & 0xFF) == 254
                    ? "the cloud instance-metadata address"
                    : "a link-local address";
            }
            if (first == 100 && second == 100 && (ipv4[2] & 0xFF) == 100
                && (ipv4[3] & 0xFF) == 200) {
                // Alibaba Cloud's metadata service. It sits inside CGNAT 100.64.0.0/10, which the
                // opt-in does lift (Tailscale and similar overlays live there), so it is named.
                return "the cloud instance-metadata address";
            }
            if (first == 0) {
                return ipv4[1] == 0 && ipv4[2] == 0 && ipv4[3] == 0
                    ? "the unspecified address"
                    : "a 0.0.0.0/8 address";
            }
            if (first >= 224 && first < 240) {
                return "a multicast address";
            }
            if (first >= 240) {
                return "a reserved (240.0.0.0/4) address";
            }
            return null;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length != 16) {
            return null;
        }
        if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xC0) == 0x80) {
            return "a link-local address";
        }
        if ((bytes[0] & 0xFF) == 0xFF) {
            return "a multicast address";
        }
        if (address.isAnyLocalAddress()) {
            return "the unspecified address";
        }
        if (isAwsImdsIpv6(bytes)) {
            // AWS's IPv6 metadata endpoint, fd00:ec2::254, inside the ULA range the opt-in lifts.
            return "the cloud instance-metadata address";
        }
        return null;
    }

    private static boolean isAwsImdsIpv6(byte[] bytes) {
        byte[] imds = {(byte) 0xFD, 0, 0x0E, (byte) 0xC2, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02,
            0x54};
        return Arrays.equals(bytes, imds);
    }

    /**
     * The four IPv4 bytes {@code address} leads to, or null: the address itself, or the one
     * embedded in {@code ::ffff:0:0/96} (mapped), {@code 64:ff9b::/96} (NAT64),
     * {@code 2002::/16} (6to4) or {@code 2001::/32} (Teredo, client address bit-inverted).
     *
     * <p>B3-11: on a host with NAT64, or a 6to4 or Teredo route, a connection to one of these is
     * a connection to the IPv4 address inside it, so that address is what the checks judge.
     */
    private static byte[] embeddedIpv4(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return bytes;
        }
        if (isIpv4Mapped(bytes) || isNat64(bytes)) {
            return Arrays.copyOfRange(bytes, 12, 16);
        }
        if ((bytes[0] & 0xFF) == 0x20 && bytes[1] == 0x02) {
            return Arrays.copyOfRange(bytes, 2, 6);
        }
        if ((bytes[0] & 0xFF) == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0) {
            return new byte[] {(byte) ~bytes[12], (byte) ~bytes[13], (byte) ~bytes[14],
                (byte) ~bytes[15]};
        }
        return null;
    }

    private static boolean isNat64(byte[] bytes) {
        return bytes[0] == 0 && bytes[1] == 0x64 && (bytes[2] & 0xFF) == 0xFF
            && (bytes[3] & 0xFF) == 0x9B && Arrays.equals(bytes, 4, 12, new byte[8], 0, 8);
    }

    /**
     * Why {@code address} may not be contacted, or {@code null} if it may.
     *
     * <p>Package-private and reason-returning so the table-driven test can assert the specific
     * range each address falls in rather than a bare boolean.
     */
    static String blockReason(InetAddress address) {
        // The family-independent checks run first so that a v4-mapped or v4-compatible IPv6
        // loopback is reported as a loopback rather than as an address-format curiosity: the
        // reason ends up in the server log (never the caller's message, B3-10), and "loopback"
        // tells an operator more than "IPv4-compatible IPv6" does.
        if (address.isAnyLocalAddress()) {
            return "the unspecified address";
        }
        if (address.isLoopbackAddress()) {
            return "a loopback address";
        }
        if (address.isLinkLocalAddress()) {
            return METADATA_IPV4.equals(address.getHostAddress())
                ? "the cloud instance-metadata address"
                : "a link-local address";
        }
        if (address.isMulticastAddress()) {
            return "a multicast address";
        }
        if (address.isSiteLocalAddress()) {
            return "a private (RFC 1918) address";
        }
        if (address instanceof Inet6Address ipv6) {
            return ipv6BlockReason(ipv6);
        }
        if (address instanceof Inet4Address ipv4) {
            return ipv4BlockReason(ipv4.getAddress());
        }
        return null;
    }

    private static String ipv6BlockReason(Inet6Address address) {
        byte[] bytes = address.getAddress();
        if (address.isIPv4CompatibleAddress()) {
            return "an IPv4-compatible IPv6 address";
        }
        if (isIpv4Mapped(bytes)) {
            return "an IPv4-mapped IPv6 address";
        }
        if ((bytes[0] & 0xFE) == 0xFC) {
            return "an IPv6 unique-local (fc00::/7) address";
        }
        byte[] ipv4 = embeddedIpv4(address);
        if (ipv4 != null) {
            String embedded;
            try {
                embedded = blockReason(InetAddress.getByAddress(ipv4));
            } catch (UnknownHostException e) {
                // getByAddress refuses only a wrong length, and this is always four bytes.
                throw new IllegalStateException(e);
            }
            return embedded == null ? null : embedded + " inside an IPv6 transition address";
        }
        return null;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    private static String ipv4BlockReason(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        int second = bytes[1] & 0xFF;
        if (first == 0) {
            return "a 0.0.0.0/8 address";
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return "a carrier-grade NAT (100.64.0.0/10) address";
        }
        if (first == 192 && second == 0 && (bytes[2] & 0xFF) == 0) {
            return "an IETF protocol-assignment (192.0.0.0/24) address";
        }
        if (first == 198 && (second == 18 || second == 19)) {
            return "a benchmarking (198.18.0.0/15) address";
        }
        if (first >= 240) {
            return "a reserved (240.0.0.0/4) address";
        }
        return null;
    }

    private List<InetAddress> resolve(String host) {
        InetAddress[] resolved;
        try {
            resolved = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw refusal(host, "it does not resolve", e);
        }
        if (resolved == null || resolved.length == 0) {
            throw refusal(host, "it does not resolve");
        }
        List<InetAddress> addresses = new ArrayList<>(resolved.length);
        for (InetAddress address : resolved) {
            if (address != null) {
                addresses.add(address);
            }
        }
        if (addresses.isEmpty()) {
            throw refusal(host, "it does not resolve");
        }
        return List.copyOf(addresses);
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new OutboundUrlPolicyException("A URL is required");
        }
        String trimmed = url.trim();
        if (trimmed.length() != url.length()) {
            throw new OutboundUrlPolicyException("The URL must not begin or end with whitespace");
        }
        try {
            URI uri = new URI(trimmed);
            if (!uri.isAbsolute()) {
                throw new OutboundUrlPolicyException("The URL must be absolute: " + trimmed);
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new OutboundUrlPolicyException("The URL is malformed", e);
        }
    }

    private static String stripBrackets(String host) {
        return host.length() > 1 && host.charAt(0) == '[' && host.endsWith("]")
            ? host.substring(1, host.length() - 1)
            : host;
    }
}
