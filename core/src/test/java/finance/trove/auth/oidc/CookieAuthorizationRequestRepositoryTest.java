package finance.trove.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.crypto.FieldEncryptionService;
import jakarta.servlet.http.Cookie;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stateless OIDC state store (§1.5, §2.1).
 *
 * <p>Written rather than kept: the class an earlier draft named,
 * {@code HttpCookieOAuth2AuthorizationRequestRepository}, does not exist in Spring Security. The
 * only shipped implementation is session-backed, and this application is
 * {@code SessionCreationPolicy.STATELESS} everywhere else.
 */
class CookieAuthorizationRequestRepositoryTest {

    private static final String KEY =
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

    private final FieldEncryptionService encryption = new FieldEncryptionService(KEY);
    private final CookieAuthorizationRequestRepository repository =
        new CookieAuthorizationRequestRepository(encryption, JsonMapper.builder().build());

    private static OAuth2AuthorizationRequest sample() {
        return OAuth2AuthorizationRequest.authorizationCode()
            .authorizationUri("https://idp.example.com/authorize")
            .clientId("trove")
            .redirectUri("https://trove.example.com/api/login/oauth2/code/oidc")
            .scopes(Set.of("openid", "email"))
            .state("state-value")
            .attributes(Map.of("registration_id", "oidc", "code_verifier", "the-pkce-verifier"))
            .additionalParameters(Map.of("code_challenge", "challenge",
                "code_challenge_method", "S256"))
            .build();
    }

    private static MockHttpServletRequest requestWith(MockHttpServletResponse response) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        String header = response.getHeaders("Set-Cookie").stream()
            .filter(value -> value.startsWith(CookieAuthorizationRequestRepository.COOKIE_NAME))
            .findFirst()
            .orElseThrow();
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
        request.setCookies(
            new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME, value));
        return request;
    }

    @Test
    @DisplayName("An authorization request round-trips through the cookie intact")
    void roundTrip() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        OAuth2AuthorizationRequest original = sample();

        repository.saveAuthorizationRequest(original, new MockHttpServletRequest(), response);
        OAuth2AuthorizationRequest loaded =
            repository.loadAuthorizationRequest(requestWith(response));

        assertThat(loaded).isNotNull();
        assertThat(loaded.getState()).isEqualTo("state-value");
        assertThat(loaded.getClientId()).isEqualTo("trove");
        assertThat(loaded.getAuthorizationUri()).isEqualTo("https://idp.example.com/authorize");
        assertThat(loaded.getRedirectUri()).isEqualTo(original.getRedirectUri());
        assertThat(loaded.getScopes()).containsExactlyInAnyOrder("openid", "email");
        assertThat(loaded.getAttributes())
            .as("the PKCE verifier is the secret half of the exchange and must survive the trip")
            .containsEntry("code_verifier", "the-pkce-verifier")
            .containsEntry("registration_id", "oidc");
        assertThat(loaded.getAdditionalParameters())
            .containsEntry("code_challenge", "challenge")
            .containsEntry("code_challenge_method", "S256");
    }

    @Test
    @DisplayName("The cookie is __Host-, HttpOnly, Secure, SameSite=Lax and five minutes long")
    void cookieAttributes() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sample(), new MockHttpServletRequest(), response);

        String header = response.getHeaders("Set-Cookie").get(0);
        assertThat(header).startsWith("__Host-trove_oidc=");
        assertThat(header).contains("HttpOnly");
        assertThat(header).contains("Secure");
        assertThat(header).contains("Path=/");
        assertThat(header)
            .as("Strict would not be sent on the provider's cross-site redirect back here, so "
                + "the state would be missing exactly when it is needed")
            .contains("SameSite=Lax");
        assertThat(header).contains("Max-Age=300");
    }

    @Test
    @DisplayName("The cookie value is ciphertext, not the state in the clear")
    void theStateIsEncrypted() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sample(), new MockHttpServletRequest(), response);

        String header = response.getHeaders("Set-Cookie").get(0);
        assertThat(header)
            .doesNotContain("the-pkce-verifier")
            .doesNotContain("state-value")
            .contains("v1:");
    }

    @Test
    @DisplayName("Removing reads and clears in one step, so the state is single-use")
    void removeIsReadAndClear() {
        MockHttpServletResponse saved = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(sample(), new MockHttpServletRequest(), saved);
        MockHttpServletRequest callback = requestWith(saved);
        MockHttpServletResponse cleared = new MockHttpServletResponse();

        assertThat(repository.removeAuthorizationRequest(callback, cleared)).isNotNull();
        assertThat(cleared.getHeaders("Set-Cookie").get(0))
            .as("leaving the cookie alive would let the same authorization code exchange be "
                + "replayed for the rest of its five minutes")
            .contains("Max-Age=0");
    }

    @Test
    @DisplayName("A tampered cookie reads as absent — GCM refuses it, the flow fails cleanly")
    void aTamperedCookieIsTreatedAsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(CookieAuthorizationRequestRepository.COOKIE_NAME,
            "v1:not-actually-ciphertext"));

        assertThat(repository.loadAuthorizationRequest(request)).isNull();
    }

    @Test
    @DisplayName("A cookie from a different encryption key reads as absent, not as a 500")
    void aCookieFromAnotherKeyIsTreatedAsAbsent() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new CookieAuthorizationRequestRepository(
            new FieldEncryptionService(
                "1f1e1d1c1b1a191817161514131211100f0e0d0c0b0a09080706050403020100"),
            JsonMapper.builder().build())
            .saveAuthorizationRequest(sample(), new MockHttpServletRequest(), response);

        assertThat(repository.loadAuthorizationRequest(requestWith(response)))
            .as("rotating TROVE_ENCRYPTION_KEY mid-login produces exactly this and must not be "
                + "an internal error")
            .isNull();
    }

    @Test
    @DisplayName("No cookie at all is absent, and saving null clears")
    void absentAndNullCases() {
        assertThat(repository.loadAuthorizationRequest(new MockHttpServletRequest())).isNull();

        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveAuthorizationRequest(null, new MockHttpServletRequest(), response);
        assertThat(response.getHeaders("Set-Cookie").get(0)).contains("Max-Age=0");
    }
}
