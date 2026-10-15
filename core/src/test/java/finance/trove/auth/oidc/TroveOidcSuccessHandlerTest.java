package finance.trove.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.auth.AuthProperties;
import finance.trove.auth.SessionResponseWriter;
import finance.trove.auth.jwt.AuthCookies;
import finance.trove.auth.jwt.IssuedToken;
import finance.trove.auth.jwt.JwtIssuerService;
import finance.trove.auth.jwt.JwtProperties;
import finance.trove.auth.jwt.JwtVerifier;
import finance.trove.auth.jwt.TokenType;
import finance.trove.auth.session.RefreshResult;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.time.UserClock;
import finance.trove.users.User;
import finance.trove.users.UserRole;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The second factor on the OIDC path.
 *
 * <p>Before this, an account with TOTP enabled got a full session out of the OIDC callback with
 * no code asked for: the handler read {@code totp.isEnabled} only to echo it into the session DTO
 * and never gated on it. So a user who deliberately turned on a second factor was protected on
 * the password login and nowhere else, and an identity provider that is itself single-factor
 * silently decided the whole login. §1.5 requires the second factor of every login, and does not
 * carve the federated one out.
 *
 * <p>These are unit tests for the same reason {@link OidcAccountLinkerTest} is: driving a real
 * callback needs a live OpenID provider to mint ID tokens with, which no environment here has.
 * The servlet handler is therefore exercised directly with Spring's mock request and response.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TroveOidcSuccessHandlerTest {

    private static final String PUBLIC_URL = "https://trove.example";
    private static final String SECRET = "oidc-success-handler-test-secret-32-chars";

    @Mock
    private OidcAccountLinker linker;

    @Mock
    private RefreshTokenService sessions;

    @Mock
    private TotpService totp;

    @Mock
    private SessionResponseWriter writer;

    @Mock
    private SecurityEventRecorder securityEvents;

    @Mock
    private UserClock clock;

    private final JwtProperties jwtProperties =
        new JwtProperties(SECRET, "k1", "trove-core", 900, 86_400, 60, 300);
    private final JwtIssuerService issuer = new JwtIssuerService(jwtProperties);
    private final JwtVerifier verifier = new JwtVerifier(jwtProperties);
    /**
     * From the real clock, to the second. A hardcoded instant made
     * {@code theChallengeIsNotPutInTheQueryString} a time bomb: it verifies the challenge cookie
     * with the real {@link finance.trove.auth.jwt.JwtVerifier}, whose clock is the system one, so
     * a sixty-second challenge minted at a fixed 2026-09-04T12:00:00Z stopped verifying two
     * minutes into that day and failed every build afterwards.
     */
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    private TroveOidcSuccessHandler handler;
    private User member;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        handler = new TroveOidcSuccessHandler(linker, issuer, sessions, totp, writer,
            new AuthProperties(false, PUBLIC_URL), jwtProperties, securityEvents, clock);
        member = new User(UUID.randomUUID(), UserRole.MEMBER, "member@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("UTC"));
        ReflectionTestUtils.setField(member, "id", UUID.randomUUID());
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        when(clock.now()).thenReturn(now);
        when(linker.resolve(anyString(), anyString(), anyString(), any()))
            .thenReturn(new OidcLinkOutcome.Linked(member, false));
        when(sessions.issue(any(), any(), any(), any()))
            .thenReturn(new RefreshResult(member.getId(), UUID.randomUUID(),
                new IssuedToken("refresh-token", "jti", now.plusSeconds(86_400)), false));
    }

    @Test
    @DisplayName("TOTP enabled: the callback ends in a challenge, not in a session")
    void totpEnabledStopsShortOfASession() throws IOException {
        when(totp.isEnabled(member.getId())).thenReturn(true);

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl())
            .as("the user is returned to the login page to finish the second factor")
            .isEqualTo(PUBLIC_URL + "/login?error=totp_required");
        verify(sessions, never()).issue(any(), any(), any(), any());
        verify(writer, never()).writeSession(any(), any(), any(), any());
        assertThat(response.getHeaders("Set-Cookie"))
            .as("no session cookie may exist before the code is answered")
            .noneMatch(header -> header.startsWith(AuthCookies.ACCESS_COOKIE)
                || header.startsWith(AuthCookies.REFRESH_COOKIE));
    }

    @Test
    @DisplayName("The challenge travels in a __Host- HttpOnly cookie, never in the URL")
    void theChallengeIsNotPutInTheQueryString() throws IOException {
        when(totp.isEnabled(member.getId())).thenReturn(true);

        handler.onAuthenticationSuccess(request, response, authentication());

        String cookie = response.getHeaders("Set-Cookie").stream()
            .filter(header -> header.startsWith(AuthCookies.TOTP_CHALLENGE_COOKIE + "="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no challenge cookie was set: "
                + response.getHeaders("Set-Cookie")));

        assertThat(cookie)
            .contains("HttpOnly")
            .contains("Secure")
            .contains("SameSite=Lax")
            .contains("Path=/")
            .as("the cookie must not outlive the sixty-second credential inside it")
            .contains("Max-Age=60");

        String token = response.getCookie(AuthCookies.TOTP_CHALLENGE_COOKIE).getValue();
        assertThat(verifier.verify(token, TokenType.TOTP_CHALLENGE).userId())
            .as("it is a real challenge for this account, redeemable at POST /v1/auth/login/totp")
            .isEqualTo(member.getId());
        assertThat(response.getRedirectedUrl())
            .as("a credential in a URL lands in browser history, the Referer of every asset that "
                + "page loads and every proxy log in front of the install")
            .doesNotContain(token);
    }

    @Test
    @DisplayName("TOTP not enabled: the ordinary OIDC login is untouched")
    void withoutTotpTheSessionIsIssuedAsBefore() throws IOException {
        when(totp.isEnabled(member.getId())).thenReturn(false);

        handler.onAuthenticationSuccess(request, response, authentication());

        verify(sessions).issue(any(), any(), any(), any());
        verify(writer).writeSession(any(), any(), any(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo(PUBLIC_URL + "/");
        assertThat(response.getCookie(AuthCookies.TOTP_CHALLENGE_COOKIE))
            .as("nothing is owed, so nothing is carried")
            .isNull();
    }

    @Test
    @DisplayName("A first-time federated registration is gated the same way")
    void aRegisteredAccountIsGatedToo() throws IOException {
        when(linker.resolve(anyString(), anyString(), anyString(), any()))
            .thenReturn(new OidcLinkOutcome.Registered(member));
        when(totp.isEnabled(member.getId())).thenReturn(true);

        handler.onAuthenticationSuccess(request, response, authentication());

        assertThat(response.getRedirectedUrl()).isEqualTo(PUBLIC_URL + "/login?error=totp_required");
        verify(sessions, never()).issue(any(), any(), any(), any());
    }

    private static TestingAuthenticationToken authentication() {
        DefaultOAuth2User principal = new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            Map.of("sub", "provider-subject-42", "email", "member@trove.test",
                "email_verified", true),
            "sub");
        return new TestingAuthenticationToken(principal, null);
    }
}
