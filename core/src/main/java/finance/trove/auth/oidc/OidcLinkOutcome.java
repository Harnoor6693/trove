package finance.trove.auth.oidc;

import finance.trove.users.User;

/**
 * What {@link OidcAccountLinker} concluded about an identity a provider just asserted.
 *
 * <p>Four outcomes rather than a boolean, because the interesting one is
 * {@link LinkRequired}: an ID token whose email matches a local account but which does not carry
 * {@code email_verified: true}. Treating that as a successful login is an account takeover —
 * anyone who can have an IdP account issued with an unverified address matching a household
 * member signs in as that member with full ledger access (§1.5, §9 risk 27). Treating it as a flat
 * refusal is unhelpful, because it is also what a legitimate first-time link looks like at a
 * provider that does not assert verification. So it is its own answer, and the user is sent to
 * prove the local password once.
 */
public sealed interface OidcLinkOutcome {

    /** A link exists, or was just created because the provider vouched for a matching address. */
    record Linked(User user, boolean newLink) implements OidcLinkOutcome {
    }

    /**
     * A local account matches the address but the provider did not assert it is verified. No
     * session is issued; the user signs in with their password once and the link is made then.
     */
    record LinkRequired(String email) implements OidcLinkOutcome {
    }

    /** No local account, and this install accepts self-serve registration, so one was created. */
    record Registered(User user) implements OidcLinkOutcome {
    }

    /** No local account and registration is closed. Nothing to log in to. */
    record Denied() implements OidcLinkOutcome {
    }
}
