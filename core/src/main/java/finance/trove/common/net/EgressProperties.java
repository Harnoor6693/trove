package finance.trove.common.net;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.egress.*} — the install-wide half of the outbound-URL policy
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.6, gap P2-10).
 *
 * <p>{@code TROVE_EGRESS_ALLOW_PRIVATE} governs <em>every</em> outbound call — the per-user AI
 * endpoint, SimpleFIN, and webhooks — not the AI endpoint alone, which is what the retired
 * {@code TROVE_AI_ALLOW_PRIVATE_ENDPOINTS} name suggested. It is the household admin's
 * "is a LAN target allowed on this install at all" switch; an individual member still has to opt
 * their own endpoint in on top of it (§6, owner decision 9). Both must be true for a private
 * target to be reachable.
 *
 * <p>"Private" means RFC 1918, loopback, carrier-grade NAT ({@code 100.64.0.0/10}) and IPv6
 * unique-local ({@code fc00::/7}). Link-local, the cloud metadata addresses, and the unspecified,
 * multicast and reserved ranges are refused whatever this is set to; see
 * {@link OutboundUrlPolicy}.
 *
 * @param allowPrivate whether a private or loopback target may be opted into
 */
@ConfigurationProperties(prefix = "trove.egress")
public record EgressProperties(boolean allowPrivate) {

    public EgressProperties {
        // Records with a single boolean bind fine from an absent property (false), which is the
        // safe default the plan requires.
    }
}
