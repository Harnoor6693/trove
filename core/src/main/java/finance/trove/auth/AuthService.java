package finance.trove.auth;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.dto.RegisterRequest;
import finance.trove.auth.jwt.IssuedToken;
import finance.trove.auth.jwt.JwtIssuerService;
import finance.trove.auth.jwt.JwtProperties;
import finance.trove.auth.jwt.JwtVerifier;
import finance.trove.auth.jwt.TokenType;
import finance.trove.auth.jwt.VerifiedToken;
import finance.trove.auth.local.InvalidCredentialsException;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.local.LockoutService;
import finance.trove.auth.session.RefreshResult;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.auth.totp.TotpException;
import finance.trove.auth.totp.TotpService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.exception.UnauthorizedException;
import finance.trove.households.Household;
import finance.trove.households.InstallHousehold;
import finance.trove.households.invites.HouseholdInvite;
import finance.trove.households.invites.InviteService;
import finance.trove.households.invites.RegistrationClosedException;
import finance.trove.users.EmailAlreadyRegisteredException;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates registration, login, the TOTP step, rotation and logout
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.1: the gateway's {@code AuthService}, rewritten).
 *
 * <p>It owns the <em>sequence</em>; the decisions belong to the services it calls.
 * {@link LocalAccountService} decides whether a password is right, {@link LockoutService} whether
 * an address has been guessing, {@link TotpService} whether a code is live,
 * {@link RefreshTokenService} whether a token has been replayed. Keeping the orchestration
 * separate is what makes the order auditable, and the order is where the security lives:
 *
 * <ol>
 *   <li><strong>The per-address gate runs before anything reads a credential.</strong> A
 *       credential-stuffing run must not get an argon2 verification per guess, and it must not be
 *       able to lock out a thousand accounts on the way past.</li>
 *   <li><strong>The TOTP check runs before any token is issued.</strong> The challenge token is
 *       issued instead of a session, never alongside one.</li>
 *   <li><strong>The refresh family is opened only at the end</strong>, so a login that fails at
 *       the second factor leaves no session row behind.</li>
 * </ol>
 *
 * <p>Nothing here formats a response or touches a cookie: {@code AuthController} does that, and
 * this class returns {@link LoginOutcome}, which cannot express "a session and a challenge at the
 * same time".
 */
@Service
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);

    /** The defaults of §4.1 when a client cannot supply them; CAD / CA / UTC. */
    private static final Jurisdiction DEFAULT_COUNTRY = Jurisdiction.CA;
    private static final CurrencyCode DEFAULT_CURRENCY = CurrencyCode.CAD;
    private static final String DEFAULT_TIMEZONE = "UTC";

    private final UserRepository users;
    private final InstallHousehold installHousehold;
    private final LocalAccountService accounts;
    private final LockoutService lockout;
    private final TotpService totp;
    private final InviteService invites;
    private final RefreshTokenService sessions;
    private final JwtIssuerService issuer;
    private final JwtVerifier verifier;
    private final JwtProperties jwtProperties;
    private final SecurityEventRecorder securityEvents;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public AuthService(UserRepository users, InstallHousehold installHousehold,
                       LocalAccountService accounts, LockoutService lockout, TotpService totp,
                       InviteService invites, RefreshTokenService sessions,
                       JwtIssuerService issuer, JwtVerifier verifier, JwtProperties jwtProperties,
                       SecurityEventRecorder securityEvents) {
        this.users = users;
        this.installHousehold = installHousehold;
        this.accounts = accounts;
        this.lockout = lockout;
        this.totp = totp;
        this.invites = invites;
        this.sessions = sessions;
        this.issuer = issuer;
        this.verifier = verifier;
        this.jwtProperties = jwtProperties;
        this.securityEvents = securityEvents;
    }

    // ---------------------------------------------------------------- registration

    /**
     * Creates an account and logs it straight in.
     *
     * <p>The household is never taken from the request body. It comes from the invite the caller
     * redeemed, or — on an install that has deliberately opened self-serve signup — from the one
     * household this install has. A body-supplied household id would be the G1 IDOR reintroduced
     * at the front door (§9 risk 2).
     */
    @Transactional
    public LoginOutcome.Session register(RegisterRequest request, String ip, String userAgent,
                                         Instant now) {
        lockout.assertAddressNotThrottled(ip, now);
        String email = normalise(request.email());
        boolean invited = request.inviteToken() != null && !request.inviteToken().isBlank();

        HouseholdInvite invite = invited
            ? invites.resolveForRegistration(request.inviteToken(), email, now)
            : null;

        UUID householdId;
        UserRole role;
        if (invite != null) {
            householdId = invite.getHouseholdId();
            role = invite.getRole();
        } else {
            Household household = openHousehold();
            householdId = household.getId();
            role = UserRole.MEMBER;
        }

        // Checked before the password is hashed: argon2id at 64 MiB is the expensive half of this
        // request, and there is no reason to spend it on a registration that cannot succeed.
        if (users.existsByEmailIgnoreCase(email)) {
            // The answer discloses that the address has an account, and has to: a new address is
            // signed straight in (EmailAlreadyRegisteredException's javadoc has the trade). What
            // is bounded is how often it can be asked: recorded as a failed sign-in from this
            // address, so the gate above refuses a prober exactly when it refuses a guesser
            // (B3-09). Committed on its own, like every failure LockoutService records.
            lockout.recordFailure(email, ip, null, now);
            throw new EmailAlreadyRegisteredException();
        }

        Jurisdiction country = request.country() == null ? DEFAULT_COUNTRY : request.country();
        CurrencyCode currency = request.currency() == null ? DEFAULT_CURRENCY : request.currency();
        User user = new User(householdId, role, email, request.displayName().strip(),
            country, currency, zone(request.timezone()));
        users.save(user);
        accounts.createCredential(user.getId(), request.password(), email, now);

        if (invite != null) {
            invites.markAccepted(invite, user.getId(), now);
        }

        LOG.info("Registered a {} into household {} ({})", role, householdId,
            invite != null ? "by invite" : "open registration");
        return openSession(user, false, false, ip, userAgent, now);
    }

    // ---------------------------------------------------------------- login

    /**
     * Password login. Returns a session, or a challenge when a second factor is enrolled.
     *
     * @throws finance.trove.auth.local.TooManyLoginAttemptsException  429, per-address gate
     * @throws finance.trove.auth.local.InvalidCredentialsException    401, identical for unknown
     *                                                                 address, wrong password,
     *                                                                 locked and deactivated
     */
    @Transactional
    public LoginOutcome login(String rawEmail, String password, String ip, String userAgent,
                              Instant now) {
        lockout.assertAddressNotThrottled(ip, now);
        User user = accounts.authenticate(normalise(rawEmail), password, ip, now);

        if (totp.isEnabled(user.getId())) {
            IssuedToken challenge = issuer.issueTotpChallengeToken(user.getId(), now);
            LOG.debug("Password accepted; a second factor is owed");
            return new LoginOutcome.TotpRequired(challenge.value());
        }
        return openSession(user, false, true, ip, userAgent, now);
    }

    /**
     * The second half of a two-factor login.
     *
     * <p>The challenge token is verified with {@link TokenType#TOTP_CHALLENGE}, so a full access
     * token presented here is refused — the mirror of the rule that refuses a challenge token
     * everywhere else (§9 risk 28).
     */
    @Transactional
    public LoginOutcome.Session completeTotpLogin(String challengeToken, String code, String ip,
                                                  String userAgent, Instant now) {
        lockout.assertAddressNotThrottled(ip, now);
        VerifiedToken challenge = verifier.verify(challengeToken, TokenType.TOTP_CHALLENGE);

        User user = users.findAccount(challenge.userId())
            .filter(User::isActive)
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired token"));

        // The account lock is re-asserted here, not merely on the password half. A challenge is
        // good for sixty seconds and the lock can arrive inside them — the account is locked
        // precisely when somebody is guessing at it — so without this the second factor was the
        // one door a locked account could still walk through. Refused with the same exception the
        // password path throws, so the body is identical and the lock stays invisible.
        if (lockout.isLockedForSecondFactor(user.getEmail(), ip, now)) {
            throw new InvalidCredentialsException();
        }

        if (!totp.verify(user.getId(), code, now)) {
            // Counted, not merely logged. Without this the per-account lock and the per-address
            // gate never move on this route, and an attacker who already has the password can
            // re-mint a sixty-second challenge at will and grind six digits — three of which are
            // live in any window once drift is allowed — with no server-side throttle at all.
            // The password half of the login is throttled; the half that is supposed to be the
            // stronger one was not.
            lockout.recordFailure(user.getEmail(), ip, user.getId(), now);
            securityEvents.record(SecurityEventType.LOGIN_FAIL, user.getId(),
                Map.of("stage", "totp"));
            throw TotpException.invalidCode();
        }

        return openSession(user, true, true, ip, userAgent, now);
    }

    // ---------------------------------------------------------------- session lifecycle

    /**
     * Rotates a refresh token and mints a matching access token.
     *
     * <p>The access token is re-derived from the {@code users} row rather than copied from the old
     * one: a role change or a deactivation must take effect at the next refresh, not at the next
     * login, or an admin who demotes somebody has to wait out a session to mean it.
     */
    @Transactional
    public LoginOutcome.Session refresh(String rawRefreshToken, String ip, String userAgent,
                                        Instant now) {
        RefreshResult rotated = sessions.rotate(rawRefreshToken, ip, userAgent, now);
        User user = users.findAccount(rotated.userId())
            .filter(User::isActive)
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired session"));
        IssuedToken access = issuer.issueAccessToken(user.getId(), user.getHouseholdId(),
            user.getEmail(), user.getRole(), rotated.familyId(), false, null, now);
        return new LoginOutcome.Session(user, totp.isEnabled(user.getId()), access,
            rotated.token());
    }

    /** Ends the family the token belongs to. Idempotent: an unknown or absent token is a no-op. */
    @Transactional
    public void logout(String rawRefreshToken, Instant now) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        sessions.revokeByToken(rawRefreshToken, now);
    }

    // ---------------------------------------------------------------- password change

    /**
     * Changes a password the owner still knows, then ends every <em>other</em> session (§4.1).
     *
     * <p>The caller's own family is spared, named by the {@code fid} claim on the token that
     * authorised this request. An earlier version revoked every family and handed the caller a
     * replacement session in the response body, which is defensible on the server and wrong
     * against the clients that exist: the mobile client then typed this call
     * {@code Promise<void>} ({@code changePassword} in {@code apps/mobile/src/api/auth.ts} now
     * returns the {@code {user}}) and treats a rejected refresh with 400, 401 or 403 as terminal
     * (the {@code endSession(REFRESH_REJECTED)} branch in {@code apps/mobile/src/api/client.ts}),
     * so the user was signed out within fifteen minutes of changing their password — while its
     * own UI told them "Password changed. Other devices have been signed out."
     * ({@code apps/mobile/app/(tabs)/settings.tsx:196}).
     *
     * <p>The security intent survives, and "immediately" is now literal: anybody who had the old
     * password and a live session on another device loses it at their very next request, because
     * the authentication filter checks each access token's refresh family against
     * {@code refresh_token} ({@code SessionValidity}). Revoking the row used to leave the access
     * token in their hands working for the rest of its fifteen minutes, which made this sentence
     * a promise the code did not keep. Only the device that performed the change keeps going, and
     * that device just proved it knows the password.
     *
     * @param currentFamilyId the caller's own refresh family, spared; {@code null} revokes all
     * @throws StepUpRequiredException 403 when the current password does not match
     */
    @Transactional
    public User changePassword(UUID userId, UUID currentFamilyId, String currentPassword,
                               String newPassword, Instant now) {
        User user = users.findAccount(userId)
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired token"));
        if (!accounts.matchesCurrentPassword(userId, currentPassword)) {
            throw StepUpRequiredException.wrongPassword();
        }
        accounts.setPassword(userId, newPassword, user.getEmail(), now);
        int revoked = sessions.revokeOtherSessions(userId, currentFamilyId, "password-change", now);
        securityEvents.record(SecurityEventType.PASSWORD_CHANGE, userId,
            Map.of("sessionsRevoked", revoked, "keptCurrent", currentFamilyId != null));
        LOG.info("A password was changed; {} other sessions revoked", revoked);
        return user;
    }

    // ---------------------------------------------------------------- helpers

    /** Access-token lifetime in seconds, for the {@code expiresIn} field of a bearer response. */
    public long accessTokenSeconds() {
        return jwtProperties.accessTtl().toSeconds();
    }

    /**
     * Opens a session.
     *
     * <p>The refresh family is created <em>before</em> the access token, not after, because the
     * access token now carries the family id ({@code fid}). Without that ordering the session
     * could not name itself, and "end every session except this one" would have to ask a bearer
     * client to hand back a token it keeps in a keystore.
     */
    private LoginOutcome.Session openSession(User user, boolean otpUsed, boolean recordLoginOk,
                                             String ip, String userAgent, Instant now) {
        RefreshResult refresh = sessions.issue(user.getId(), ip, userAgent, now);
        IssuedToken access = issuer.issueAccessToken(user.getId(), user.getHouseholdId(),
            user.getEmail(), user.getRole(), refresh.familyId(), otpUsed, null, now);
        if (recordLoginOk) {
            securityEvents.record(SecurityEventType.LOGIN_OK, user.getId(),
                Map.of("factor", otpUsed ? "password+totp" : "password"));
        }
        return new LoginOutcome.Session(user, totp.isEnabled(user.getId()), access,
            refresh.token());
    }

    /**
     * The household a self-serve registration joins.
     *
     * @throws RegistrationClosedException when the install has not opened signup, and also when it
     *                                     has no household at all — an install that has never been
     *                                     bootstrapped has nothing to join, and "closed" is the
     *                                     honest answer rather than a 500
     */
    private Household openHousehold() {
        return installHousehold.requireOpenForRegistration();
    }

    private static String normalise(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses an IANA zone, falling back to UTC.
     *
     * <p>A browser that reports a zone this JRE does not know — an out-of-date tzdb, an exotic
     * alias — must not lose a registration over it. The consequence of the fallback is that day
     * boundaries are UTC until the user picks a zone in settings, which is visible and fixable;
     * the consequence of throwing is a signup form that cannot be submitted at all.
     */
    private static ZoneId zone(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
        try {
            return ZoneId.of(candidate.strip());
        } catch (DateTimeException e) {
            LOG.warn("Registration supplied an unrecognised timezone; defaulting to {}",
                DEFAULT_TIMEZONE);
            return ZoneId.of(DEFAULT_TIMEZONE);
        }
    }
}
