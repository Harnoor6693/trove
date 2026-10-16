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
 *       owner decision 9. Only the per-user AI endpoint passes an opt-in; SimpleFIN and webhooks
 *       call {@link #check(String)}, so the flag never opens a private target to them. With both
 *       keys set the policy lifts the private-network blocks only:
 *       RFC 1918, loopback, CGNAT, ULA and the deprecated IPv6 site-local {@code fec0::/10}.
 *       Link-local (including {@code 169.254.169.254}), the cloud metadata addresses that sit
 *       inside those ranges (Alibaba's in CGNAT; AWS's, Google's, Oracle's, Akamai's and
 *       Scaleway's IPv6 ones in ULA), the unspecified, multicast and reserved ranges, every
 *       other IPv6 special-purpose range IANA marks not globally reachable, and the IPv6 space
 *       outside {@code 2000::/3} that the IETF reserves stay refused whatever the keys say; see
 *       {@link #unconditionalBlockReason}.</li>
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

    /**
     * Cloud metadata and credential endpoints that sit inside the ULA range {@code fc00::/7},
     * which the opt-in lifts, so each one is named. Only addresses the provider documents (or,
     * for Oracle, ships in its own SDK) are listed. Checked 2026-10-16: Azure, DigitalOcean,
     * Hetzner, Vultr and Alibaba document IPv4 only (link-local, or Alibaba's
     * {@code 100.100.100.200}, both refused already); Akamai's second address,
     * {@code fe80::a9fe:a9fe}, is link-local and refused already.
     */
    private static final List<byte[]> METADATA_IPV6 = List.of(
        // AWS EC2 IMDS:
        // https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-service.html
        ipv6Literal("fd00:ec2::254"),
        // AWS EKS Pod Identity Agent, which hands pods their IAM credentials:
        // https://docs.aws.amazon.com/eks/latest/userguide/pod-id-agent-setup.html
        ipv6Literal("fd00:ec2::23"),
        // Google Compute Engine:
        // https://docs.cloud.google.com/compute/docs/metadata/querying-metadata
        ipv6Literal("fd20:ce::254"),
        // Oracle Cloud. Its IMDS page lists IPv4 only; Oracle's own SDK uses this address
        // (IMDS_IPV6_HOST) and fetches the instance-principal key from it:
        // https://github.com/oracle/oci-python-sdk/blob/master/src/oci/auth/signers/instance_principals_security_token_signer.py
        ipv6Literal("fd00:c1::a9fe:a9fe"),
        // Akamai (Linode):
        // https://techdocs.akamai.com/cloud-computing/docs/metadata-service-api
        ipv6Literal("fd00:a9fe:a9fe::1"),
        // Scaleway:
        // https://www.scaleway.com/en/developers/api/instance/user-data
        ipv6Literal("fd00:42::42"));

    /**
     * Entries of the IANA IPv6 Special-Purpose Address Registry marked not globally reachable
     * that no other rule covers. Not globally reachable means no public endpoint can be there,
     * and none of them is a private network a LAN model lives on, so the opt-in does not lift
     * them. https://www.iana.org/assignments/iana-ipv6-special-registry/ (checked 2026-10-16,
     * registry last updated 2025-10-09).
     */
    private static final List<Ipv6Prefix> NOT_GLOBALLY_REACHABLE = List.of(
        prefix("100::", 64, "a discard-only (100::/64) address"),
        prefix("100:0:0:1::", 64, "a dummy-prefix (100:0:0:1::/64) address"),
        prefix("2001:2::", 48, "a benchmarking (2001:2::/48) address"),
        prefix("2001:10::", 28, "a deprecated ORCHID (2001:10::/28) address"),
        prefix("2001:db8::", 32, "a documentation (2001:db8::/32) address"),
        prefix("3fff::", 20, "a documentation (3fff::/20) address"),
        prefix("5f00::", 16, "an SRv6 SID (5f00::/16) address"));

    /**
     * {@code 2001::/23}: the registry marks it not globally reachable "unless allowed by a more
     * specific allocation", so an address in it is refused unless it falls in one of
     * {@link #GLOBALLY_REACHABLE_ASSIGNMENTS}.
     */
    private static final Ipv6Prefix IETF_PROTOCOL_ASSIGNMENTS =
        prefix("2001::", 23, "an IETF protocol-assignment (2001::/23) address");

    /**
     * The more specific {@code 2001::/23} entries the registry marks globally reachable, plus
     * Teredo ({@code 2001::/32}, "N/A" there), which is judged by the IPv4 address it carries.
     * ORCHIDv2 and DETs are host identifiers, not locators (RFC 7343), but the registry calls
     * them globally reachable and they reach nothing without a HIP stack, so they stay allowed.
     */
    private static final List<Ipv6Prefix> GLOBALLY_REACHABLE_ASSIGNMENTS = List.of(
        prefix("2001::", 32, "Teredo"),
        prefix("2001:1::1", 128, "Port Control Protocol anycast"),
        prefix("2001:1::2", 128, "TURN anycast"),
        prefix("2001:1::3", 128, "DNS-SD Service Registration Protocol anycast"),
        prefix("2001:3::", 32, "AMT"),
        prefix("2001:4:112::", 48, "AS112-v6"),
        prefix("2001:20::", 28, "ORCHIDv2"),
        prefix("2001:30::", 28, "Drone Remote ID entity tags"));

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
     * whose answer is the host's own credentials; the metadata addresses that sit inside ranges
     * the opt-in does lift, Alibaba's {@code 100.100.100.200} (in CGNAT) and the IPv6 ones in
     * {@link #METADATA_IPV6} (in ULA); and the ranges that are not private networks at all, which
     * no LAN model lives on: unspecified ({@code 0.0.0.0/8}, {@code ::}), multicast
     * ({@code 224.0.0.0/4}, {@code ff00::/8}), reserved ({@code 240.0.0.0/4}, and the IPv6 space
     * outside {@code 2000::/3} that the IETF reserves, see {@link #isIetfReserved}) and the other
     * IPv6 special-purpose ranges IANA marks not globally reachable
     * ({@link #NOT_GLOBALLY_REACHABLE}, and {@code 2001::/23} outside its globally reachable
     * entries). The opt-in exists so a member can point at a LAN model on
     * RFC 1918, loopback, CGNAT, ULA or site-local; treating
     * the rest as one setting is what would turn a per-user endpoint into an SSRF primitive
     * against the install itself, with no administrator anywhere in the path, since every member
     * configures their own endpoint.
     *
     * <p>An IPv6 address is judged twice, and either refusal refuses. First as itself, against
     * the native IPv6 ranges above plus the local-use NAT64 prefix, before anything else: an
     * ISATAP identifier under {@code fe80::/10}, {@code ff00::/8} or {@code 2001:db8::/32} is
     * still link-local, multicast or documentation. Then by every IPv4 address it may carry
     * (see {@link #embeddedIpv4s}), because {@code ::ffff:169.254.169.254} is the same
     * destination written differently and {@code Inet6Address#isLinkLocalAddress} answers false
     * for it.
     */
    static String unconditionalBlockReason(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            String reason = nativeIpv6BlockReason(address, bytes);
            if (reason != null) {
                return reason;
            }
        }
        for (byte[] ipv4 : embeddedIpv4s(bytes)) {
            String reason = unconditionalIpv4BlockReason(ipv4);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static String unconditionalIpv4BlockReason(byte[] ipv4) {
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

    /** The always-refused verdict on an IPv6 address as itself, before any IPv4 inside it. */
    private static String nativeIpv6BlockReason(InetAddress address, byte[] bytes) {
        if (address.isAnyLocalAddress()) {
            return "the unspecified address";
        }
        if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xC0) == 0x80) {
            return "a link-local address";
        }
        if ((bytes[0] & 0xFF) == 0xFF) {
            return "a multicast address";
        }
        for (byte[] metadata : METADATA_IPV6) {
            if (Arrays.equals(bytes, metadata)) {
                return "the cloud instance-metadata address";
            }
        }
        if (isLocalUseNat64(bytes)) {
            return "a local-use NAT64 (64:ff9b:1::/48) address";
        }
        for (Ipv6Prefix prefix : NOT_GLOBALLY_REACHABLE) {
            if (prefix.contains(bytes)) {
                return prefix.reason();
            }
        }
        if (IETF_PROTOCOL_ASSIGNMENTS.contains(bytes)
            && GLOBALLY_REACHABLE_ASSIGNMENTS.stream().noneMatch(p -> p.contains(bytes))) {
            return IETF_PROTOCOL_ASSIGNMENTS.reason();
        }
        if (isIetfReserved(bytes)) {
            return "a reserved IPv6 address";
        }
        return null;
    }

    /**
     * Whether {@code bytes} is space the IANA IPv6 Address Space registry marks "Reserved by
     * IETF" with no use the policy recognises: anything outside global unicast {@code 2000::/3}
     * other than ULA ({@code fc00::/7}, which the opt-in lifts), site-local ({@code fec0::/10},
     * see {@link #blockReason}), and the {@code ::/8} forms that are loopback or carry an IPv4
     * address ({@code ::/96}, {@code ::ffff:0:0/96}, {@code ::ffff:0:0:0/96},
     * {@code 64:ff9b::/96}), which {@link #embeddedIpv4s} judges. Like {@code 240.0.0.0/4}, none
     * of it holds a public endpoint or a LAN model. Unspecified, link-local, multicast and
     * local-use NAT64 are refused before this is asked.
     *
     * <p>ponytail: fail-closed on purpose, with a ceiling. RFC 4291 §2.4 says implementations
     * "must treat all addresses that do not start with any of the above-listed prefixes as
     * Global Unicast addresses"; this method refuses them instead. If IANA later opens a block
     * outside {@code 2000::/3} for global unicast, targets there stay refused until this method
     * is updated, and that is the upgrade path. Unallocated space inside {@code 2000::/3} (for
     * example {@code 3ffe::/16} and {@code 2000::/16}) is allowed, as RFC 4291 requires.
     */
    private static boolean isIetfReserved(byte[] bytes) {
        int first = bytes[0] & 0xFF;
        if ((first & 0xE0) == 0x20 || (first & 0xFE) == 0xFC
            || (first == 0xFE && (bytes[1] & 0xC0) == 0xC0)) {
            return false;
        }
        boolean zeroFirst96 = Arrays.equals(bytes, 0, 12, new byte[12], 0, 12);
        return !(zeroFirst96 || isIpv4Mapped(bytes) || isSiit(bytes) || isNat64(bytes));
    }

    /** An IPv6 prefix, and the reason an address inside it is refused (or allowed). */
    private record Ipv6Prefix(byte[] network, int length, String reason) {

        boolean contains(byte[] address) {
            int whole = length / 8;
            if (!Arrays.equals(address, 0, whole, network, 0, whole)) {
                return false;
            }
            if (length % 8 == 0) {
                return true;
            }
            int mask = (0xFF << (8 - length % 8)) & 0xFF;
            return (address[whole] & mask) == (network[whole] & mask);
        }
    }

    private static Ipv6Prefix prefix(String literal, int length, String reason) {
        return new Ipv6Prefix(ipv6Literal(literal), length, reason);
    }

    /** The bytes of an IPv6 literal; {@code getByName} does no DNS lookup for a literal. */
    private static byte[] ipv6Literal(String literal) {
        try {
            return InetAddress.getByName(literal).getAddress();
        } catch (UnknownHostException e) {
            // Only a malformed constant in this file gets here.
            throw new IllegalStateException(e);
        }
    }

    /**
     * Every IPv4 address {@code bytes} may lead to: the address itself if it is IPv4, else the one
     * embedded by each transition format it matches: {@code ::ffff:0:0/96} (mapped),
     * {@code ::/96} (IPv4-compatible), {@code ::ffff:0:0:0/96} (SIIT), {@code 64:ff9b::/96}
     * (well-known NAT64), ISATAP (identifier {@code 0:5efe} under any prefix), {@code 2002::/16}
     * (6to4) and {@code 2001::/32} (Teredo, client address bit-inverted). Empty for a native
     * IPv6 address.
     *
     * <p>B3-11: on a host with NAT64, or a 6to4, Teredo or ISATAP route, a connection to one of
     * these is a connection to the IPv4 address inside it, so that address is what the checks
     * judge. One address can match several formats ({@code 2002:a9fe:a9fe::5efe:808:808} is 6to4
     * to {@code 169.254.169.254} and ISATAP to {@code 8.8.8.8}), and which one the network uses
     * is not knowable here, so every candidate is returned and callers refuse if any is refused.
     */
    private static List<byte[]> embeddedIpv4s(byte[] bytes) {
        if (bytes.length == 4) {
            return List.of(bytes);
        }
        List<byte[]> candidates = new ArrayList<>(3);
        if (isIpv4Mapped(bytes) || isIpv4Compatible(bytes) || isSiit(bytes) || isNat64(bytes)
            || isIsatap(bytes)) {
            candidates.add(Arrays.copyOfRange(bytes, 12, 16));
        }
        if ((bytes[0] & 0xFF) == 0x20 && bytes[1] == 0x02) {
            candidates.add(Arrays.copyOfRange(bytes, 2, 6));
        }
        if ((bytes[0] & 0xFF) == 0x20 && bytes[1] == 0x01 && bytes[2] == 0 && bytes[3] == 0) {
            candidates.add(new byte[] {(byte) ~bytes[12], (byte) ~bytes[13], (byte) ~bytes[14],
                (byte) ~bytes[15]});
        }
        return candidates;
    }

    /**
     * {@code ::/96} (deprecated IPv4-compatible): twelve zero bytes then an IPv4 address. Java
     * keeps these as {@link Inet6Address}, so without this they carried no embedded address and
     * {@code ::a9fe:a9fe} slipped past {@link #unconditionalBlockReason} and the opt-in. Only
     * {@code ::} and {@code ::1}, the IPv6 unspecified and loopback addresses, are not embedded
     * IPv4 and are left to the checks that already name them; {@code ::2} is {@code 0.0.0.2}.
     */
    private static boolean isIpv4Compatible(byte[] bytes) {
        for (int i = 0; i < 12; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        boolean unspecifiedOrLoopback = bytes[12] == 0 && bytes[13] == 0 && bytes[14] == 0
            && (bytes[15] == 0 || bytes[15] == 1);
        return !unspecifiedOrLoopback;
    }

    /** {@code ::ffff:0:0:0/96} (SIIT, IPv4-translated): the IPv4 is the last 32 bits. */
    private static boolean isSiit(byte[] bytes) {
        for (int i = 0; i < 8; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return (bytes[8] & 0xFF) == 0xFF && (bytes[9] & 0xFF) == 0xFF
            && bytes[10] == 0 && bytes[11] == 0;
    }

    /**
     * ISATAP: interface identifier {@code 0000:5efe} or {@code 0200:5efe} (the universal/local
     * bit may be set) under any prefix, then the IPv4 address in the last 32 bits.
     */
    private static boolean isIsatap(byte[] bytes) {
        return (bytes[8] & 0xFD) == 0 && bytes[9] == 0
            && (bytes[10] & 0xFF) == 0x5E && (bytes[11] & 0xFF) == 0xFE;
    }

    /**
     * {@code 64:ff9b:1::/48} (RFC 8215 local-use NAT64). Where the IPv4 sits depends on the prefix
     * length the operator chose, so no position can be trusted: the whole /48 is refused, before
     * any embedded-IPv4 format is looked at.
     */
    private static boolean isLocalUseNat64(byte[] bytes) {
        return bytes[0] == 0 && bytes[1] == 0x64 && (bytes[2] & 0xFF) == 0xFF
            && (bytes[3] & 0xFF) == 0x9B && bytes[4] == 0 && bytes[5] == 1;
    }

    /** {@code 64:ff9b::/96} (RFC 6052 well-known NAT64): the IPv4 is the last 32 bits. */
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
            // Java's site-local is RFC 1918 for IPv4 and fec0::/10 for IPv6. The latter is
            // deprecated (RFC 3879, IANA: "Reserved by IETF"), but RFC 3879 §4 lets existing
            // deployments keep using it, so it stays a private range the opt-in lifts.
            return address instanceof Inet6Address
                ? "a site-local (fec0::/10, deprecated) address"
                : "a private (RFC 1918) address";
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
        for (byte[] ipv4 : embeddedIpv4s(bytes)) {
            String embedded;
            try {
                embedded = blockReason(InetAddress.getByAddress(ipv4));
            } catch (UnknownHostException e) {
                // getByAddress refuses only a wrong length, and this is always four bytes.
                throw new IllegalStateException(e);
            }
            if (embedded != null) {
                return embedded + " inside an IPv6 transition address";
            }
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
