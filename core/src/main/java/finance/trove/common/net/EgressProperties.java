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
 * @param allowPrivate whether any private, loopback or link-local target may be opted into
 */
@ConfigurationProperties(prefix = "trove.egress")
public record EgressProperties(boolean allowPrivate) {

    public EgressProperties {
        // Records with a single boolean bind fine from an absent property (false), which is the
        // safe default the plan requires.
    }
}
