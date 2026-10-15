package finance.trove.auth.oidc;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * The wire form of an in-flight authorization request, as it is written into the
 * {@code __Host-trove_oidc} cookie.
 *
 * <p>An explicit record rather than Java serialization of {@link OAuth2AuthorizationRequest}.
 * The ciphertext is AES-GCM under this install's key so it cannot be forged, but
 * {@code ObjectInputStream} on anything that has left the process is a habit worth not forming,
 * and this way the cookie's contents are a stated, reviewable list rather than whatever fields the
 * Spring Security class happens to carry in a given version.
 *
 * <p>{@code attributes} holds the PKCE {@code code_verifier} and the registration id;
 * {@code additionalParameters} holds {@code code_challenge} and {@code code_challenge_method}.
 * Both are string-valued in every path Spring Security takes for an authorization-code flow.
 *
 * @param authorizationUri     the provider's authorization endpoint
 * @param clientId             this install's client id
 * @param redirectUri          where the provider sends the browser back
 * @param scopes               requested scopes
 * @param state                the CSRF value the provider echoes
 * @param attributes           request attributes, PKCE verifier included
 * @param additionalParameters extra query parameters, PKCE challenge included
 */
public record OidcAuthorizationState(
    String authorizationUri,
    String clientId,
    String redirectUri,
    Set<String> scopes,
    String state,
    Map<String, String> attributes,
    Map<String, String> additionalParameters) {

    public OidcAuthorizationState {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        additionalParameters =
            additionalParameters == null ? Map.of() : Map.copyOf(additionalParameters);
    }

    public static OidcAuthorizationState from(OAuth2AuthorizationRequest request) {
        return new OidcAuthorizationState(
            request.getAuthorizationUri(),
            request.getClientId(),
            request.getRedirectUri(),
            new LinkedHashSet<>(request.getScopes()),
            request.getState(),
            stringify(request.getAttributes()),
            stringify(request.getAdditionalParameters()));
    }

    /** Rebuilds the Spring Security request; the {@code authorizationRequestUri} is derived. */
    public OAuth2AuthorizationRequest toAuthorizationRequest() {
        return OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri(authorizationUri)
            .clientId(clientId)
            .redirectUri(redirectUri)
            .scopes(new LinkedHashSet<>(scopes))
            .state(state)
            .attributes(new LinkedHashMap<>(attributes))
            .additionalParameters(new LinkedHashMap<>(additionalParameters))
            .build();
    }

    private static Map<String, String> stringify(Map<String, Object> source) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (value != null) {
                    copy.put(key, String.valueOf(value));
                }
            });
        }
        return copy;
    }
}
