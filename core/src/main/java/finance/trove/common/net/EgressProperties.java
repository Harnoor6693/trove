package finance.trove.common.net;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.egress.*} — the install-wide half of the per-user AI endpoint's
 * private-target opt-in ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.6, gap P2-10).
 *
 * <p>{@code TROVE_EGRESS_ALLOW_PRIVATE} is the household admin's "may a member point their AI
 * endpoint at a LAN target on this install at all" switch; the member still has to opt their own
 * endpoint in on top of it (§6, owner decision 9), and a private target is reachable only when
 * both are true. The same two keys let that endpoint use plain http and an IPv6 address in the
 * deprecated IPv4-compatible ({@code ::/96}) or the IPv4-mapped ({@code ::ffff:0:0/96}) form
 * (its IPv4 address is still judged). It widens nothing else. SimpleFIN and webhook URLs always
 * go through the
 * default policy ({@link OutboundUrlPolicy#check(String)}, which never passes an opt-in), so
 * they are refused a private target whatever this is set to.
 *
 * <p>"Private" means RFC 1918, loopback, carrier-grade NAT ({@code 100.64.0.0/10}), IPv6
 * unique-local ({@code fc00::/7}) and the deprecated IPv6 site-local ({@code fec0::/10}).
 * Link-local, the cloud metadata addresses, the unspecified, multicast and reserved ranges, every
 * other IPv4 or IPv6 range IANA marks not globally reachable, and the IPv6 space outside
 * {@code 2000::/3} that the IETF reserves are refused whatever this is set to; see
 * {@link OutboundUrlPolicy}.
 *
 * @param allowPrivate whether a private or loopback target, over https or plain http, may be
 *                     opted into
 */
@ConfigurationProperties(prefix = "trove.egress")
public record EgressProperties(boolean allowPrivate) {

    public EgressProperties {
        // Records with a single boolean bind fine from an absent property (false), which is the
        // safe default the plan requires.
    }
}
