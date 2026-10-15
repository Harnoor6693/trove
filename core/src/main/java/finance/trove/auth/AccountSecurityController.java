package finance.trove.auth;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.dto.AuthUser;
import finance.trove.auth.dto.LoginResponse;
import finance.trove.auth.dto.PasswordChangeRequest;
import finance.trove.auth.dto.PasswordResetLinkRequest;
import finance.trove.auth.dto.PasswordResetLinkResponse;
import finance.trove.auth.dto.PasswordResetRequest;
import finance.trove.auth.dto.TotpConfirmRequest;
import finance.trove.auth.dto.TotpConfirmResponse;
import finance.trove.auth.dto.TotpDisableRequest;
import finance.trove.auth.dto.TotpSetupRequest;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.reset.PasswordResetService;
import finance.trove.auth.totp.TotpEnrolment;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.exception.UnauthorizedException;
import finance.trove.common.time.UserClock;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The credential-management half of {@code /v1/auth} (§4.1; readiness gaps P2-6 and P2-8).
 *
 * <p>Every route here except the two reset endpoints requires a session <em>and</em> a re-proof of
 * the password. A session token is what an attacker who borrowed an unlocked laptop already has;
 * it is not evidence of who is at the keyboard, and every action on this controller changes how
 * the account is secured. That is why each body carries a password rather than relying on the
 * bearer token alone (§1.5, gap P2-6), and why a wrong one answers
 * {@link StepUpRequiredException} (403) rather than 401 — 401 would send every client interceptor
 * off to refresh a session that is perfectly valid.
 */
@RestController
@RequestMapping("/v1/auth")
public class AccountSecurityController {

    private final AuthService auth;
    private final SessionResponseWriter writer;
    private final LocalAccountService accounts;
    private final TotpService totp;
    private final PasswordResetService reset;
    private final UserRepository users;
    private final SecurityEventRecorder securityEvents;
    private final UserClock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public AccountSecurityController(AuthService auth, SessionResponseWriter writer,
                                     LocalAccountService accounts, TotpService totp,
                                     PasswordResetService reset, UserRepository users,
                                     SecurityEventRecorder securityEvents, UserClock clock) {
        this.auth = auth;
        this.writer = writer;
        this.accounts = accounts;
        this.totp = totp;
        this.reset = reset;
        this.users = users;
        this.securityEvents = securityEvents;
        this.clock = clock;
    }

    /**
     * {@code POST /v1/auth/password} — change a password the owner still knows.
     *
     * <p>Ends every <em>other</em> session and leaves this one working. The response carries the
     * user and no tokens: the caller's existing pair is still valid, so re-issuing one would put
     * a live credential in a second place for no benefit, and the shipped mobile client discards
     * this body anyway ({@code apps/mobile/src/api/auth.ts:76}).
     */
    @PostMapping("/password")
    public ApiResponse<LoginResponse> changePassword(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody PasswordChangeRequest request) {

        User user = auth.changePassword(principal.getUserId(), principal.getFamilyId(),
            request.currentPassword(), request.newPassword(), clock.now());
        return ApiResponse.ok(LoginResponse.cookie(
            AuthUser.of(user, totp.isEnabled(user.getId()))));
    }

    /**
     * {@code POST /v1/auth/password/reset-request} — ADMIN only (gap P2-8).
     *
     * <p>Password reset here is not an email, because most self-hosted installs have no mail
     * server. It is an admin minting a one-hour single-use link and handing it over out of band.
     * That is also why the route is admin-scoped rather than public: a public route that mints
     * reset links, with no second channel to deliver them through, would simply be a reset oracle.
     */
    @PostMapping("/password/reset-request")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PasswordResetLinkResponse> requestReset(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody PasswordResetLinkRequest request) {

        return ApiResponse.ok(reset.issue(request.email(), principal.getUserId(),
            principal.getHouseholdId(), clock.now()));
    }

    /** {@code POST /v1/auth/password/reset} — public; the token is the whole authorisation. */
    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<Void>> redeemReset(
        @Valid @RequestBody PasswordResetRequest request, HttpServletResponse httpResponse) {

        reset.redeem(request.token(), request.password(), clock.now());
        // Any browser that still holds cookies for this account is holding a revoked family.
        writer.clear(httpResponse);
        return ResponseEntity.ok(ApiResponse.<Void>ok(null));
    }

    /**
     * {@code POST /v1/auth/totp/setup} — begins enrolment behind a fresh password.
     *
     * <p>The secret is returned but the factor is <em>not</em> enabled: {@code enabled} stays
     * false until {@code /totp/confirm} sees one live code. The failure this ordering prevents is
     * a user who scans a QR code badly, turns on two-factor, and locks themselves out of their own
     * finance server with no reset path (gap P2-6).
     */
    @PostMapping("/totp/setup")
    public ApiResponse<TotpEnrolment> setupTotp(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody TotpSetupRequest request) {

        User user = requireUser(principal);
        requirePassword(principal, request.password());
        return ApiResponse.ok(totp.beginEnrolment(user));
    }

    /** {@code POST /v1/auth/totp/confirm} — one live code enables it and mints the ten codes. */
    @PostMapping("/totp/confirm")
    public ApiResponse<TotpConfirmResponse> confirmTotp(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody TotpConfirmRequest request) {

        List<String> codes = totp.confirmEnrolment(principal.getUserId(), request.code(),
            clock.now());
        return ApiResponse.ok(new TotpConfirmResponse(codes));
    }

    /**
     * {@code DELETE /v1/auth/totp} — removes the second factor behind a full step-up.
     *
     * <p>Password <em>and</em> a current code. Requiring only one of them would make the second
     * factor removable by exactly the attacker it exists to stop: whoever has the password but not
     * the phone, or whoever has the unlocked device but not the password.
     */
    @DeleteMapping("/totp")
    public ApiResponse<Void> disableTotp(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody TotpDisableRequest request) {

        requirePassword(principal, request.password());
        if (!totp.verify(principal.getUserId(), request.code(), clock.now())) {
            securityEvents.record(SecurityEventType.LOGIN_FAIL, principal.getUserId(),
                Map.of("stage", "step-up-totp"));
            throw StepUpRequiredException.wrongCode();
        }
        totp.disable(principal.getUserId(), principal.getUserId());
        return ApiResponse.<Void>ok(null);
    }

    /**
     * {@code POST /v1/auth/totp/disable} — the same operation as {@code DELETE /v1/auth/totp},
     * with the same body.
     *
     * <p>Two verbs for one action because a DELETE that carries a request body is legal but
     * poorly supported: RFC 9110 leaves its semantics undefined, and intermediaries — proxies,
     * some CDNs, a few HTTP clients — drop the body without saying so. The step-up here needs a
     * password and a code, so a dropped body turns a deliberate action into an unexplained 400 in
     * front of a user who is trying to change how their account is secured. The DELETE stays
     * because the mobile client already calls it; this is the one that always works.
     */
    @PostMapping("/totp/disable")
    public ApiResponse<Void> disableTotpByPost(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody TotpDisableRequest request) {
        return disableTotp(principal, request);
    }

    private void requirePassword(TroveUserDetails principal, String password) {
        if (!accounts.matchesCurrentPassword(principal.getUserId(), password)) {
            securityEvents.record(SecurityEventType.LOGIN_FAIL, principal.getUserId(),
                Map.of("stage", "step-up-password"));
            throw StepUpRequiredException.wrongPassword();
        }
    }

    private User requireUser(TroveUserDetails principal) {
        return users.findByIdAndHouseholdId(principal.getUserId(), principal.getHouseholdId())
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired token"));
    }

    private static String userAgent(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(HttpHeaders.USER_AGENT)).orElse(null);
    }
}
