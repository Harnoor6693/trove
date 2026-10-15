package finance.trove.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.auth.*} — the two install-wide auth settings that are not a sub-system's
 * own ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §1.6, gap P2-7).
 *
 * <p>{@code registrationOpen} here is the <em>seed</em> for a fresh install, not the live value.
 * The live value is {@code households.registration_open} (V3), because an admin flips it at
 * runtime from {@code PUT /v1/admin/settings} and a container restart must not silently revert
 * their decision to whatever the environment file still says. {@code FirstRunBootstrap} copies
 * this into the household row the one time it creates it.
 *
 * <p>{@code publicUrl} is the origin a household member types into a browser. It is not derivable
 * from the request: an invite link is minted by an admin and pasted into a chat, so it has to be
 * absolute, and the {@code Host} header of the admin's own request is attacker-influenceable and
 * would be a link-poisoning vector if it were used instead.
 *
 * @param registrationOpen seed for {@code households.registration_open}; closed by default,
 *                         because an open {@code /register} on the public internet lets strangers
 *                         create accounts on a household's finance server (gap P2-7)
 * @param publicUrl        origin for generated links, no trailing slash; an invite URL is
 *                         {@code publicUrl + "/invite/" + token}
 */
@ConfigurationProperties(prefix = "trove.auth")
public record AuthProperties(boolean registrationOpen, String publicUrl) {

    public AuthProperties {
        publicUrl = publicUrl == null || publicUrl.isBlank()
            ? "http://localhost:5173"
            : publicUrl.strip();
        while (publicUrl.endsWith("/")) {
            publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
        }
    }

    /** The absolute URL a household member opens to redeem {@code token}. */
    public String inviteUrl(String token) {
        return publicUrl + "/invite/" + token;
    }

    /**
     * The absolute URL a client opens to begin an OIDC sign-in.
     *
     * <p>Absolute, and composed from {@code publicUrl} rather than from the request: the mobile
     * client types this field as "the absolute URL the client opens"
     * ({@code apps/mobile/src/api/types.ts:153-158}) and hands it to a system browser, which has
     * no origin to resolve a relative path against. It points at {@code /v1/auth/start} — the
     * stable entry point that 302s on to whatever the provider configuration currently is — so
     * the advertised link does not change when the registration id or Spring's endpoint layout
     * does.
     */
    public String oidcStartUrl() {
        return publicUrl + "/api/v1/auth/start";
    }

    /** The absolute URL a household member opens to set a new password. */
    public String passwordResetUrl(String token) {
        return publicUrl + "/reset-password/" + token;
    }
}
