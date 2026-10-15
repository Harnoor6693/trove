package finance.trove.auth.oidc;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code trove.auth.oidc.*} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §1.6).
 *
 * <p>OIDC is optional and off by default. Everything in {@code finance.trove.auth.oidc} is
 * {@code @ConditionalOnProperty}-gated on {@link #enabled}, so on an install that never sets
 * {@code TROVE_OIDC_ISSUER_URI} no bean in this package is created, no OAuth2 client registration
 * exists, and {@code /oauth2/**} is not routed at all — an install with no identity provider must
 * not carry a second, half-configured login path.
 *
 * <p>Validation is all-or-nothing (§1.6): a partially filled block is an operator mistake that
 * would otherwise surface as an opaque redirect failure in front of a user, so it fails the boot.
 *
 * @param enabled     master switch; when false every other value is ignored
 * @param issuerUri   the OpenID Provider's issuer, from which discovery reads the endpoints
 * @param clientId    this install's client id at that provider
 * @param clientSecret this install's client secret
 * @param displayName the label the login page puts on the button
 * @param scopes      requested scopes; {@code openid} is always included
 */
@ConfigurationProperties(prefix = "trove.auth.oidc")
public record OidcProperties(
    boolean enabled,
    String issuerUri,
    String clientId,
    String clientSecret,
    String displayName,
    String scopes) {

    /** The Spring Security registration id; the plan renames Authentik's to this generic one. */
    public static final String REGISTRATION_ID = "oidc";

    public OidcProperties {
        issuerUri = blankToNull(issuerUri);
        clientId = blankToNull(clientId);
        clientSecret = blankToNull(clientSecret);
        displayName = displayName == null || displayName.isBlank()
            ? "Single sign-on" : displayName.strip();
        scopes = scopes == null || scopes.isBlank() ? "openid,profile,email" : scopes.strip();

        if (enabled && (issuerUri == null || clientId == null || clientSecret == null)) {
            throw new IllegalStateException(
                "trove.auth.oidc.enabled is true but the block is incomplete. "
                    + "TROVE_OIDC_ISSUER_URI, TROVE_OIDC_CLIENT_ID and TROVE_OIDC_CLIENT_SECRET "
                    + "are all required together (collapse plan §1.6); set all of them or set "
                    + "TROVE_OIDC_ENABLED=false.");
        }
    }

    /**
     * Spring Security's own authorization endpoint, including the {@code /api} context path.
     *
     * <p>Internal: it is where {@code GET /v1/auth/start} redirects, and it is deliberately
     * <em>not</em> what {@code /v1/auth/providers} advertises. The advertised link is
     * {@code AuthProperties#oidcStartUrl()}, an absolute URL built from {@code publicUrl}, because
     * a client that is not a same-origin browser — mobile opens it in a system browser — cannot
     * resolve a relative path against anything.
     */
    public String authorizationRequestPath() {
        return "/api/oauth2/authorization/" + REGISTRATION_ID;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
