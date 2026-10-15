package finance.trove.auth.oidc;

import finance.trove.common.crypto.EncryptionException;
import finance.trove.common.crypto.FieldEncryptionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps the in-flight OIDC authorization request — state and PKCE verifier — in an encrypted
 * cookie instead of an HTTP session ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §2.1).
 *
 * <p><strong>This class had to be written; it is not a Spring Security class.</strong> An earlier
 * draft of the plan listed {@code HttpCookieOAuth2AuthorizationRequestRepository} as something to
 * "keep", and no such type exists in Spring Security — the framework ships only the
 * session-backed {@code HttpSessionOAuth2AuthorizationRequestRepository}. Using that one would
 * mean creating an HTTP session for the OIDC flow, and the application is
 * {@code SessionCreationPolicy.STATELESS} everywhere else, so it would be the one place a
 * server-side session existed, on the one flow that redirects through a third party.
 *
 * <p>Three properties make the cookie safe to trust when it comes back:
 * <ul>
 *   <li><strong>AES-GCM under {@code TROVE_ENCRYPTION_KEY}.</strong> GCM is authenticated, so a
 *       modified cookie fails to decrypt rather than decoding into a different request. The PKCE
 *       verifier inside must also stay confidential — it is the secret half of the exchange.</li>
 *   <li><strong>Five-minute max-age.</strong> An abandoned login leaves nothing usable behind, and
 *       the browser discards it without the server tracking anything.</li>
 *   <li><strong>{@code __Host-} prefix, {@code HttpOnly}, {@code Secure}, {@code SameSite=Lax}.
 *       </strong> Lax rather than Strict for the same reason the session cookies are Lax: the
 *       provider returns the browser here by cross-site redirect, and a Strict cookie is not sent
 *       on that navigation — the state would be missing exactly when it is needed.</li>
 * </ul>
 *
 * <p>A cookie that cannot be decrypted is treated as absent. Spring Security then reports the
 * authorization request as missing and the flow fails cleanly at the failure handler, which is
 * what should happen when a key has been rotated or a cookie has been tampered with.
 */
public class CookieAuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final Logger LOG =
        LoggerFactory.getLogger(CookieAuthorizationRequestRepository.class);

    /** {@code __Host-} is browser-enforced: Secure, Path=/, no Domain. */
    public static final String COOKIE_NAME = "__Host-trove_oidc";

    /** Long enough to type a password at an identity provider, short enough to be worthless. */
    public static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final FieldEncryptionService encryption;
    private final ObjectMapper objectMapper;

    public CookieAuthorizationRequestRepository(FieldEncryptionService encryption,
                                                ObjectMapper objectMapper) {
        this.encryption = encryption;
        this.objectMapper = objectMapper;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return read(request).orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                         HttpServletRequest request,
                                         HttpServletResponse response) {
        if (authorizationRequest == null) {
            removeCookie(response);
            return;
        }
        String json = objectMapper.writeValueAsString(
            OidcAuthorizationState.from(authorizationRequest));
        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        write(response, encryption.encrypt(payload), MAX_AGE.toSeconds());
    }

    /**
     * Reads and clears in one step.
     *
     * <p>Clearing here is what makes the state single-use: the callback is the only consumer, and
     * leaving the cookie alive afterwards would let the same authorization code exchange be
     * replayed for the rest of the five minutes.
     */
    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        Optional<OAuth2AuthorizationRequest> found = read(request);
        removeCookie(response);
        return found.orElse(null);
    }

    private Optional<OAuth2AuthorizationRequest> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
            .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
            .map(Cookie::getValue)
            .filter(value -> value != null && !value.isEmpty())
            .findFirst()
            .flatMap(this::decode);
    }

    private Optional<OAuth2AuthorizationRequest> decode(String cookieValue) {
        try {
            String payload = encryption.decrypt(cookieValue);
            byte[] json = Base64.getUrlDecoder().decode(payload);
            OidcAuthorizationState state = objectMapper.readValue(
                new String(json, StandardCharsets.UTF_8), OidcAuthorizationState.class);
            return Optional.of(state.toAuthorizationRequest());
        } catch (EncryptionException | JacksonException | IllegalArgumentException e) {
            // swallow-ok: an undecodable state cookie is indistinguishable from no state cookie
            // and must be handled the same way — the flow fails at the failure handler with a
            // redirect the user can act on. Rotating TROVE_ENCRYPTION_KEY mid-login produces
            // exactly this, and it is not an error worth a 500.
            LOG.debug("Discarding an unreadable OIDC state cookie");
            return Optional.empty();
        }
    }

    private void removeCookie(HttpServletResponse response) {
        write(response, "", 0);
    }

    private static void write(HttpServletResponse response, String value, long maxAgeSeconds) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE_NAME, value)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAgeSeconds)
            .build()
            .toString());
    }
}
