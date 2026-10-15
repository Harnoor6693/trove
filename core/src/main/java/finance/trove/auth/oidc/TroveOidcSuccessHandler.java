package finance.trove.auth.oidc;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.AuthMode;
import finance.trove.auth.AuthProperties;
import finance.trove.auth.LoginOutcome;
import finance.trove.auth.SessionResponseWriter;
import finance.trove.auth.jwt.AuthCookies;
import finance.trove.auth.jwt.IssuedToken;
import finance.trove.auth.jwt.JwtIssuerService;
import finance.trove.auth.jwt.JwtProperties;
import finance.trove.auth.session.RefreshResult;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.time.UserClock;
import finance.trove.users.User;
import finance.trove.common.net.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * Turns a completed OIDC callback into a Trove session, or into a redirect that explains why not
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5, §2.1).
 *
 * <p>It is servlet glue only. Every decision belongs to {@link OidcAccountLinker}, which is where
 * the {@code email_verified} rule that §9 risk 27 turns on can be tested without a browser or a
 * live identity provider.
 *
 * <p>The browser is always redirected — never handed JSON — because this is the tail of a
 * full-page navigation the user started by clicking a link. Cookie mode is the only mode here for
 * the same reason: there is no client-side code in the loop to catch a token out of a body.
 *
 * <p>The error keys in the redirect are the ones the login page already understands:
 * {@code link_required}, {@code denied} and {@code oidc}
 * ({@code apps/web/src/pages/Login/errorCopy.ts:40-44}). Anything unrecognised there falls back to
 * the generic OIDC message, so an unknown key degrades rather than breaks.
 */
public class TroveOidcSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TroveOidcSuccessHandler.class);

    private final OidcAccountLinker linker;
    private final JwtIssuerService issuer;
    private final RefreshTokenService sessions;
    private final TotpService totp;
    private final SessionResponseWriter writer;
    private final AuthProperties properties;
    private final JwtProperties jwtProperties;
    private final SecurityEventRecorder securityEvents;
    private final UserClock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public TroveOidcSuccessHandler(OidcAccountLinker linker, JwtIssuerService issuer,
                                   RefreshTokenService sessions, TotpService totp,
                                   SessionResponseWriter writer, AuthProperties properties,
                                   JwtProperties jwtProperties,
                                   SecurityEventRecorder securityEvents, UserClock clock) {
        this.linker = linker;
        this.issuer = issuer;
        this.sessions = sessions;
        this.totp = totp;
        this.writer = writer;
        this.properties = properties;
        this.jwtProperties = jwtProperties;
        this.securityEvents = securityEvents;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OidcLinkOutcome outcome = resolve(authentication);
        Instant now = clock.now();

        switch (outcome) {
            case OidcLinkOutcome.Linked linked -> {
                if (challengeSecondFactor(response, linked.user(), now)) {
                    return;
                }
                issueSession(request, response, linked.user(), now);
                if (linked.newLink()) {
                    securityEvents.record(SecurityEventType.LOGIN_OK, linked.user().getId(),
                        Map.of("factor", "oidc", "link", "created"));
                } else {
                    securityEvents.record(SecurityEventType.LOGIN_OK, linked.user().getId(),
                        Map.of("factor", "oidc"));
                }
                response.sendRedirect(properties.publicUrl() + "/");
            }
            case OidcLinkOutcome.Registered registered -> {
                if (challengeSecondFactor(response, registered.user(), now)) {
                    return;
                }
                issueSession(request, response, registered.user(), now);
                securityEvents.record(SecurityEventType.LOGIN_OK, registered.user().getId(),
                    Map.of("factor", "oidc", "registered", true));
                response.sendRedirect(properties.publicUrl() + "/onboarding");
            }
            case OidcLinkOutcome.LinkRequired required -> {
                LOG.info("OIDC sign-in needs a password proof before the link is made");
                response.sendRedirect(properties.publicUrl() + "/login?error=link_required");
            }
            case OidcLinkOutcome.Denied denied ->
                response.sendRedirect(properties.publicUrl() + "/login?error=denied");
        }
    }

    /**
     * Reads the three claims the decision needs.
     *
     * <p>{@code email_verified} arrives as a boolean from a compliant provider and as the string
     * {@code "true"} from several that are not, so both are accepted — and anything else,
     * including absence, is false. Being generous about the shape of the claim and strict about
     * its value is the right way round: a provider that spells it wrongly should not silently be
     * read as "verified".
     */
    OidcLinkOutcome resolve(Authentication authentication) {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        String subject = principal instanceof OidcUser oidcUser
            ? oidcUser.getSubject()
            : String.valueOf(principal.getAttributes().get("sub"));
        String email = asString(principal.getAttributes().get("email"));
        Boolean verified = asBoolean(principal.getAttributes().get("email_verified"));
        return linker.resolve(OidcProperties.REGISTRATION_ID, subject, email, verified);
    }

    /**
     * Stops an OIDC sign-in one step short of a session when the account has TOTP enabled.
     *
     * <p>Without this the identity provider's word was the whole login: an account that had
     * deliberately turned on a second factor got a full session from a provider that may itself
     * be single-factor, so enabling TOTP protected the password login and nothing else. The
     * password path has always answered {@code {challengeToken}} here (§1.5); this makes the OIDC
     * path answer the same thing in the only way a full-page redirect can.
     *
     * <p>The challenge travels in a short-lived {@code __Host-} HttpOnly cookie rather than the
     * redirect's query string. A credential in a URL is written to the browser's history and
     * address bar, sent as the {@code Referer} of anything that page loads, and logged by every
     * proxy in front of the install; sixty seconds of validity does not make that acceptable.
     *
     * @return {@code true} when the caller must stop — the redirect has already been written
     */
    private boolean challengeSecondFactor(HttpServletResponse response, User user, Instant now)
        throws IOException {
        if (!totp.isEnabled(user.getId())) {
            return false;
        }
        IssuedToken challenge = issuer.issueTotpChallengeToken(user.getId(), now);
        AuthCookies.setTotpChallengeCookie(response, challenge.value(),
            jwtProperties.totpChallengeTtl());
        // No security_event row: the password path records none for its own challenge either
        // (AuthService#login), and a half-login is not yet an outcome worth an audit row. The
        // LOGIN_OK that follows the answered challenge is the event.
        LOG.info("OIDC sign-in completed the first factor; a TOTP code is still owed");
        response.sendRedirect(properties.publicUrl() + "/login?error=totp_required");
        return true;
    }

    private void issueSession(HttpServletRequest request, HttpServletResponse response, User user,
                              Instant now) {
        // Refresh first, so the access token can name its own family in the `fid` claim.
        RefreshResult refresh = sessions.issue(user.getId(), ClientIpResolver.resolve(request),
            request.getHeader("User-Agent"), now);
        IssuedToken access = issuer.issueAccessToken(user.getId(), user.getHouseholdId(),
            user.getEmail(), user.getRole(), refresh.familyId(), false, null, now);
        writer.writeSession(response, AuthMode.COOKIE,
            new LoginOutcome.Session(user, totp.isEnabled(user.getId()), access, refresh.token()),
            now);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value instanceof String text ? Boolean.valueOf("true".equalsIgnoreCase(text)) : null;
    }
}
