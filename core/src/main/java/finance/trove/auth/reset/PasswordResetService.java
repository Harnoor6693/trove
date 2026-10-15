package finance.trove.auth.reset;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.AuthProperties;
import finance.trove.auth.dto.PasswordResetLinkResponse;
import finance.trove.auth.local.LocalAccountService;
import finance.trove.auth.session.RefreshTokenService;
import finance.trove.common.exception.TroveException;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password reset without a mail server (readiness gap P2-8).
 *
 * <p>The premise: most self-hosters have no SMTP, so a forgotten password otherwise means editing
 * the database or reinstalling. Instead a household admin generates a one-hour, single-use link
 * and hands it over through whatever channel they already use. The console equivalent
 * ({@code trove admin reset-password}, {@code finance.trove.app.cli.AdminConsole}) covers the case
 * this cannot: the last administrator is the one locked out, and there is no admin session left to
 * issue anything. It calls {@link #issueFromConsole}.
 *
 * <p>Three properties that are not optional:
 * <ul>
 *   <li><strong>Issuing a link invalidates any earlier one.</strong> An admin who generates a
 *       second link because the first was mistyped must not leave two live resets on one account.
 *   <li><strong>Redeeming revokes every session, including the access tokens already issued from
 *       them.</strong> A reset is what someone does when they think their password is known to
 *       somebody else; leaving that somebody else's refresh tokens alive would defeat the
 *       exercise (§1.5), and so would leaving their access token good for another fifteen
 *       minutes — which is what revoking the rows alone did until the authentication filter began
 *       checking each token's family ({@code SessionValidity}).</li>
 *   <li><strong>The new password goes through the ordinary policy.</strong> A reset is not a
 *       back door around the length and deny-list checks.</li>
 * </ul>
 */
@Service
public class PasswordResetService {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetService.class);

    /** §1.5: one hour. Long enough to paste into a chat, short enough to be worthless later. */
    private static final Duration TTL = Duration.ofHours(1);

    /** 32 bytes of entropy, URL-safe base64 — 43 characters, no padding, no escaping needed. */
    private static final int TOKEN_BYTES = 32;

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final LocalAccountService accounts;
    private final RefreshTokenService sessions;
    private final AuthProperties properties;
    private final SecurityEventRecorder securityEvents;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(PasswordResetTokenRepository tokens, UserRepository users,
                                LocalAccountService accounts, RefreshTokenService sessions,
                                AuthProperties properties,
                                SecurityEventRecorder securityEvents) {
        this.tokens = tokens;
        this.users = users;
        this.accounts = accounts;
        this.sessions = sessions;
        this.properties = properties;
        this.securityEvents = securityEvents;
    }

    /**
     * Mints a link for {@code email}. Admin-only at the controller.
     *
     * @param actorHouseholdId the admin's household; an address outside it is answered 404
     * @throws TroveException 404 when no such account exists <em>in this household</em>. This
     *                        endpoint is not enumeration-safe within a household and does not
     *                        need to be: it is authenticated and restricted to an admin, who can
     *                        already list its members. Pretending to succeed would only mean an
     *                        admin pasting a dead link to somebody and neither of them knowing
     *                        why.
     */
    @Transactional
    public PasswordResetLinkResponse issue(String email, UUID actorId, UUID actorHouseholdId,
                                           Instant now) {
        // Scoped to the admin's OWN household. Resolving by email across the whole install would
        // let an admin of one household mint a working reset link for a member of another — an
        // account takeover dressed as an administrative action. It is theoretical while the
        // owner's decision is one household per install, and it is three lines to get right now
        // versus a migration to get right later (§9 risk 2).
        User user = users.findByEmailIgnoreCase(email)
            .filter(candidate -> candidate.getHouseholdId().equals(actorHouseholdId))
            .orElseThrow(() -> new TroveException(
                "No account in this household uses that email address.",
                HttpStatus.NOT_FOUND, "not-found"));

        return mint(user, actorId, "admin", now);
    }

    /**
     * Mints a link from the server console, for an address anywhere on the install.
     *
     * <p>The one case the admin route above cannot serve: the install's only administrator is the
     * one who is locked out, so there is no admin session to authenticate with and no second
     * admin to ask. This resolves the address across the whole install rather than within a
     * household, because a console caller has no household — they have shell access to the
     * machine the database is on, which is a strictly stronger credential than any session.
     *
     * <p>The audit row records no actor. "Somebody at the console did this" is the honest
     * statement; attributing it to the subject would make it indistinguishable from a
     * self-service reset, and attributing it to an administrator would name someone who may not
     * exist.
     *
     * @throws TroveException 404 when the address matches no account on this install
     */
    @Transactional
    public PasswordResetLinkResponse issueFromConsole(String email, Instant now) {
        User user = users.findByEmailIgnoreCase(email)
            .orElseThrow(() -> new TroveException(
                "No account on this install uses that email address.",
                HttpStatus.NOT_FOUND, "not-found"));
        return mint(user, null, "console", now);
    }

    private PasswordResetLinkResponse mint(User user, UUID actorId, String channel, Instant now) {
        tokens.consumeAllForUser(user.getId(), now);

        String raw = randomToken();
        Instant expiresAt = now.plus(TTL);
        tokens.save(new PasswordResetToken(user.getId(), hash(raw), actorId, expiresAt));

        securityEvents.record(SecurityEventType.PASSWORD_RESET, user.getId(), actorId,
            Map.of("stage", "link-issued", "channel", channel));
        LOG.info("A password reset link was issued for a member of household {}",
            user.getHouseholdId());
        return new PasswordResetLinkResponse(properties.passwordResetUrl(raw), expiresAt);
    }

    /**
     * Redeems a link and sets the new password.
     *
     * @throws TroveException 400 when the token is unknown, expired or already used — one answer
     *                        for all three, so the endpoint cannot be used to test token guesses
     *                        for near-misses
     */
    @Transactional
    public void redeem(String rawToken, String newPassword, Instant now) {
        // Locked: a concurrent redeem of the same link waits here and then finds it consumed.
        Optional<PasswordResetToken> maybe = tokens.findByTokenHashForUpdate(hash(rawToken));
        PasswordResetToken token = maybe.filter(t -> t.isUsableAt(now))
            .orElseThrow(PasswordResetService::invalidLink);

        // Loaded without a household filter, and this is the one place that is correct: the
        // caller is anonymous by design, so there is no principal to take a household from, and
        // the household is a property OF the user row rather than an input to finding it. The
        // token's uniqueness is the authorisation. A member deactivated or deleted after the link
        // was minted is refused like an unknown link: their account cannot sign in, and a reset
        // must not quietly rewrite its password during the deletion grace.
        User user = users.findAccount(token.getUserId())
            .filter(User::isActive)
            .orElseThrow(PasswordResetService::invalidLink);

        // Policy first: if the new password is refused, the link must survive so the member can
        // try again rather than having to ask for a second one.
        accounts.setPassword(user.getId(), newPassword, user.getEmail(), now);

        token.consume(now);
        tokens.save(token);
        accounts.unlock(user.getId());
        int revoked = sessions.revokeAllForUser(user.getId(), "password-reset", now);

        securityEvents.record(SecurityEventType.PASSWORD_RESET, user.getId(), user.getId(),
            Map.of("stage", "redeemed", "sessionsRevoked", revoked));
        LOG.info("A password was reset through a link; {} sessions revoked", revoked);
    }

    /** Daily sweep (§5.6 {@code export-link-expiry-sweep} sibling); step 4 schedules it. */
    @Transactional
    public int purgeExpired(Instant cutoff) {
        return tokens.purgeExpiredBefore(cutoff);
    }

    private static TroveException invalidLink() {
        return new TroveException(
            "This password reset link is not valid. It may have expired or already been used.",
            HttpStatus.BAD_REQUEST, "reset-invalid");
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex; the raw token never reaches the database (V9.1). */
    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE specification", e);
        }
    }
}
