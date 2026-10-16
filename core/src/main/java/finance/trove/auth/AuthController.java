package finance.trove.auth;

import finance.trove.auth.dto.InvitePreviewResponse;
import finance.trove.auth.dto.LoginRequest;
import finance.trove.auth.dto.LoginResponse;
import finance.trove.auth.dto.ProvidersResponse;
import finance.trove.auth.dto.RefreshRequest;
import finance.trove.auth.dto.RefreshResponse;
import finance.trove.auth.dto.RegisterRequest;
import finance.trove.auth.dto.TotpLoginRequest;
import finance.trove.auth.jwt.AuthCookies;
import finance.trove.auth.oidc.OidcProperties;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.UnauthorizedException;
import finance.trove.common.time.UserClock;
import finance.trove.common.net.ClientIpResolver;
import finance.trove.households.InstallHousehold;
import finance.trove.households.invites.InviteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public half of {@code /v1/auth} — the sign-in, session and discovery routes
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.1).
 *
 * <p>Every body here is the {@code ApiResponse} envelope. §4.1 states that explicitly because an
 * earlier draft left it ambiguous while every other route family used the envelope; the only
 * JSON exception in the whole API is {@code GET /v1/status}, which is bare so that an uptime
 * check has something trivial to key off (file downloads, the chat stream and the OIDC redirect
 * are not JSON bodies at all).
 *
 * <p>The credential-management half (password change, reset, TOTP enrolment) lives in
 * {@link AccountSecurityController}. Most of it needs a session, but
 * {@code POST /v1/auth/password/reset} is public and is listed in {@code
 * SecurityConfig.PUBLIC_POST}.
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final SessionResponseWriter writer;
    private final InviteService invites;
    private final InstallHousehold installHousehold;
    private final AuthProperties authProperties;
    private final ObjectProvider<OidcProperties> oidc;
    private final UserClock clock;

    public AuthController(AuthService auth, SessionResponseWriter writer, InviteService invites,
                          InstallHousehold installHousehold, AuthProperties authProperties,
                          ObjectProvider<OidcProperties> oidc,
                          UserClock clock) {
        this.auth = auth;
        this.writer = writer;
        this.invites = invites;
        this.installHousehold = installHousehold;
        this.authProperties = authProperties;
        this.oidc = oidc;
        this.clock = clock;
    }

    /**
     * {@code POST /v1/auth/register} — 201 with the same body shape as a login.
     *
     * <p>Registration is closed by default; the ordinary path is redeeming an invite an admin
     * minted (gap P2-7). The household is taken from that invite, never from this body.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<LoginResponse>> register(
        @Valid @RequestBody RegisterRequest request,
        @RequestHeader(name = AuthMode.HEADER, required = false) String modeHeader,
        HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        AuthMode mode = AuthMode.from(modeHeader);
        Instant now = clock.now();
        LoginOutcome.Session session = auth.register(request, clientIp(httpRequest),
            userAgent(httpRequest), now);
        writer.writeSignIn(httpRequest, httpResponse, mode, session, now);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(writer.body(mode, session, auth.accessTokenSeconds())));
    }

    /**
     * {@code POST /v1/auth/login}.
     *
     * <p>A second factor being owed is a <strong>200 success</strong> carrying
     * {@code {challengeToken}}, not an error status. The clients discriminate on the presence of
     * that field inside a successful envelope ({@code apps/web/src/pages/Login/index.tsx:81},
     * {@code isTotpChallenge}); an
     * error status would never reach that check and the user would be told their password was
     * wrong instead of being shown the code screen.
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
        @Valid @RequestBody LoginRequest request,
        @RequestHeader(name = AuthMode.HEADER, required = false) String modeHeader,
        HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        AuthMode mode = AuthMode.from(modeHeader);
        Instant now = clock.now();
        LoginOutcome outcome = auth.login(request.email(), request.password(),
            clientIp(httpRequest), userAgent(httpRequest), now);
        return ResponseEntity.ok(ApiResponse.ok(
            render(outcome, mode, httpRequest, httpResponse, now)));
    }

    /**
     * {@code POST /v1/auth/login/totp} — the challenge token is the only credential accepted.
     *
     * <p>The token comes from the body when client code held it (the password login), and from
     * the {@code __Host-trove_totp_challenge} cookie when nothing did (the OIDC redirect, see
     * {@code TroveOidcSuccessHandler}). Body first, so an explicit token always wins over a stale
     * cookie left by an abandoned OIDC attempt. Neither present is a 401, the same answer a bad
     * token gets: this route never says which part of a failed second factor was missing.
     */
    @PostMapping("/login/totp")
    public ResponseEntity<ApiResponse<LoginResponse>> loginTotp(
        @Valid @RequestBody TotpLoginRequest request,
        @RequestHeader(name = AuthMode.HEADER, required = false) String modeHeader,
        HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        AuthMode mode = AuthMode.from(modeHeader);
        Instant now = clock.now();
        String challengeToken = request.presentedChallengeToken();
        if (challengeToken == null) {
            challengeToken = AuthCookies.readTotpChallengeCookie(httpRequest)
                .orElseThrow(() -> new UnauthorizedException("Invalid or expired token"));
        }
        LoginOutcome.Session session = auth.completeTotpLogin(challengeToken,
            request.code(), clientIp(httpRequest), userAgent(httpRequest), now);
        // The challenge has been spent; leaving the cookie in place would present it again at the
        // next login attempt, where it can only produce a confusing failure.
        AuthCookies.clearTotpChallengeCookie(httpResponse);
        writer.writeSignIn(httpRequest, httpResponse, mode, session, now);
        return ResponseEntity.ok(
            ApiResponse.ok(writer.body(mode, session, auth.accessTokenSeconds())));
    }

    /**
     * {@code POST /v1/auth/refresh} — rotation, with the reuse-grace window of §1.5.
     *
     * <p>The body is optional and may be literally {@code null}: the web client posts no body at
     * all and relies on {@code __Host-trove_refresh} ({@code apps/web/src/api/client.ts:229}),
     * while mobile puts the token in the body. Reading the body first and the cookie second
     * serves both from one route without a mode header.
     *
     * <p>A failed rotation clears the cookies. Otherwise a browser holding a revoked family would
     * keep presenting it on every request for the rest of the cookie's max-age, and every one of
     * those is another {@code REFRESH_REUSE_DETECTED} row about a session that is already dead.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(
        @RequestBody(required = false) RefreshRequest request,
        @RequestHeader(name = AuthMode.HEADER, required = false) String modeHeader,
        HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        // Where the token came from decides the mode, not the header alone: a token that arrived
        // in a cookie came from a browser, and answering it with tokens in the body would put the
        // session somewhere a script can read it whatever header was sent.
        boolean fromBody = bodyCarriedToken(request);
        AuthMode mode = fromBody ? AuthMode.from(modeHeader) : AuthMode.COOKIE;
        String presented = presentedRefreshToken(request, httpRequest);
        if (presented == null) {
            throw new UnauthorizedException("Invalid or expired session");
        }
        Instant now = clock.now();
        try {
            LoginOutcome.Session session = auth.refresh(presented, clientIp(httpRequest),
                userAgent(httpRequest), now);
            // writeSession, not writeSignIn: the same refresh family, so the same session, and
            // the SPA replays the request that 401'd with the X-XSRF-TOKEN it first carried
            // (apps/web/src/api/client.ts). A token replaced here would 403 every replayed write.
            writer.writeSession(httpResponse, mode, session, now);
            return ResponseEntity.ok(ApiResponse.ok(
                writer.refreshBody(mode, session, auth.accessTokenSeconds())));
        } catch (UnauthorizedException e) {
            writer.clear(httpResponse);
            throw e;
        }
    }

    /** {@code POST /v1/auth/logout} — idempotent; an unknown or absent token is still a 200. */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
        @RequestBody(required = false) RefreshRequest request,
        HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        auth.logout(presentedRefreshToken(request, httpRequest), clock.now());
        writer.clear(httpResponse);
        return ResponseEntity.ok(ApiResponse.<Void>ok(null));
    }

    /**
     * {@code GET /v1/auth/providers} — public; the login page renders itself from this.
     *
     * <p>{@code registrationOpen} is read from the household row rather than from configuration,
     * so an admin's runtime decision is what the login page reflects.
     */
    @GetMapping("/providers")
    public ApiResponse<ProvidersResponse> providers() {
        OidcProperties properties = oidc.getIfAvailable();
        ProvidersResponse.Oidc provider = properties != null && properties.enabled()
            ? ProvidersResponse.Oidc.enabled(authProperties.oidcStartUrl(),
                properties.displayName())
            : ProvidersResponse.Oidc.disabled();
        return ApiResponse.ok(
            new ProvidersResponse(registrationOpen(), true, provider));
    }

    /** {@code GET /v1/auth/invites/{token}} — public; always 200, verdict in the body. */
    @GetMapping("/invites/{token}")
    public ApiResponse<InvitePreviewResponse> previewInvite(@PathVariable String token) {
        return ApiResponse.ok(invites.preview(token, clock.now()));
    }

    /**
     * {@code GET /v1/auth/start} — the OIDC entry point the web login page links to
     * ({@code oidcStartUrl} in {@code apps/web/src/api/auth.ts:132}, linked from
     * {@code apps/web/src/pages/Login/index.tsx:218}).
     *
     * <p>A 302 to Spring Security's own authorization endpoint rather than linking there directly,
     * so the client has one stable URL whose behaviour changes with configuration instead of a
     * hardcoded link that 404s on the majority of installs that never configure a provider.
     */
    @GetMapping("/start")
    public ResponseEntity<Void> startOidc() {
        OidcProperties properties = oidc.getIfAvailable();
        if (properties == null || !properties.enabled()) {
            throw new ResourceNotFoundException(
                "Single sign-on is not configured on this install.");
        }
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create(properties.authorizationRequestPath()))
            .build();
    }

    private LoginResponse render(LoginOutcome outcome, AuthMode mode, HttpServletRequest request,
                                 HttpServletResponse response, Instant now) {
        return switch (outcome) {
            case LoginOutcome.TotpRequired challenge ->
                LoginResponse.challenge(challenge.challengeToken());
            case LoginOutcome.Session session -> {
                writer.writeSignIn(request, response, mode, session, now);
                yield writer.body(mode, session, auth.accessTokenSeconds());
            }
        };
    }

    private boolean registrationOpen() {
        return installHousehold.registrationOpen();
    }

    private static boolean bodyCarriedToken(RefreshRequest request) {
        return request != null && request.refreshToken() != null
            && !request.refreshToken().isBlank();
    }

    private static String presentedRefreshToken(RefreshRequest request,
                                                HttpServletRequest httpRequest) {
        if (bodyCarriedToken(request)) {
            return request.refreshToken();
        }
        return AuthCookies.readRefreshCookie(httpRequest).filter(t -> !t.isBlank()).orElse(null);
    }

    /**
     * The caller's address, from the one resolver every per-address control in the application
     * shares ({@link ClientIpResolver}).
     *
     * <p>Never a hand-parsed {@code X-Forwarded-For}: the pre-collapse limiter took the first XFF
     * value unconditionally, which any client can set, so every per-IP control keyed on it was
     * spoofable (gap P2-5). Collapse step 4 added the trusted-proxy valve that makes the socket
     * address the real client address behind Traefik, and this method is now a one-line delegation
     * kept only so the call sites read as they did.
     */
    private static String clientIp(HttpServletRequest request) {
        return ClientIpResolver.resolve(request);
    }

    private static String userAgent(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(HttpHeaders.USER_AGENT)).orElse(null);
    }
}
