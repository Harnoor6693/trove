package finance.trove.households.invites;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.AuthProperties;
import finance.trove.auth.dto.CreateInviteRequest;
import finance.trove.auth.dto.InvitePreviewResponse;
import finance.trove.auth.dto.InviteResponse;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single-use, time-boxed household invitations (readiness gap P2-7).
 *
 * <p>Registration is closed by default, because an open {@code /register} on the public internet
 * lets strangers create accounts on somebody's finance server. The way in is an invite an admin
 * mints and pastes into whatever channel they already talk to their household in — no SMTP
 * required, which matters because most self-hosted installs have none.
 *
 * <p>An invite pre-binds the {@code household_id}, which is what makes household membership
 * something the server decides rather than something a registration body asserts. It can also
 * bind an email address, in which case the registration that redeems it must use exactly that
 * address.
 *
 * <p>The SHA-256 hash is the only lookup key. Alongside it the raw token is kept AES-GCM-encrypted
 * (V9.2) so {@link #list} can hand an admin the link again: the web admin UI discards the creation
 * response and renders {@code url} from the listing
 * ({@code apps/web/src/pages/Settings/index.tsx:166-169, 215}), so a listing without it makes the
 * invite feature unusable from the only UI there is. The plaintext leaves this service only for an
 * invite that is still usable, and only to an admin of its own household; accepting or revoking
 * one clears the ciphertext, and an expired one is never decrypted.
 */
@Service
public class InviteService {

    private static final Logger LOG = LoggerFactory.getLogger(InviteService.class);

    /** §1.5 / gap P2-7: 72 hours. */
    public static final Duration TTL = Duration.ofHours(72);

    private static final int TOKEN_BYTES = 32;

    private final HouseholdInviteRepository invites;
    private final UserRepository users;
    private final AuthProperties properties;
    private final SecurityEventRecorder securityEvents;
    private final FieldEncryptionService encryption;
    private final SecureRandom random = new SecureRandom();

    public InviteService(HouseholdInviteRepository invites, UserRepository users,
                         AuthProperties properties, SecurityEventRecorder securityEvents,
                         FieldEncryptionService encryption) {
        this.invites = invites;
        this.users = users;
        this.properties = properties;
        this.securityEvents = securityEvents;
        this.encryption = encryption;
    }

    /** Mints an invite into {@code householdId}. The raw token is in the returned object only. */
    @Transactional
    public InviteResponse create(UUID householdId, UUID actorId, CreateInviteRequest request,
                                 Instant now) {
        CreateInviteRequest body = request == null ? CreateInviteRequest.empty() : request;
        String email = normalise(body.email());
        String raw = randomToken();
        HouseholdInvite invite = new HouseholdInvite(householdId, email, body.roleOrDefault(),
            hash(raw), encryption.encrypt(raw), actorId, now, now.plus(TTL));
        invites.save(invite);

        securityEvents.record(SecurityEventType.INVITE_CREATED, actorId, actorId,
            Map.of("inviteId", invite.getId().toString(), "bound", email != null,
                "role", body.roleOrDefault().name()));
        LOG.info("Invite {} minted into household {} (bound={})",
            invite.getId(), householdId, email != null);
        return toResponse(invite, raw, actorEmail(actorId));
    }

    /**
     * Every invite for one household, newest first.
     *
     * <p>{@code token} and {@code url} are present only for an invite that can still be redeemed.
     * A row that has been accepted or revoked has no ciphertext left to decrypt, and an expired
     * one is not decrypted even though its ciphertext is still there — handing back a link that
     * cannot work is worse than handing back none, and the narrower the window in which plaintext
     * exists at all, the better.
     */
    @Transactional(readOnly = true)
    public List<InviteResponse> list(UUID householdId, Instant now) {
        return invites.findAllByHouseholdIdOrderByCreatedAtDesc(householdId).stream()
            .map(invite -> toResponse(invite, readableToken(invite, now),
                actorEmail(invite.getCreatedBy())))
            .toList();
    }

    /** The raw token of a still-usable invite, or {@code null} for one that is spent. */
    private String readableToken(HouseholdInvite invite, Instant now) {
        if (!invite.isUsableAt(now) || invite.getTokenEncrypted() == null) {
            return null;
        }
        return encryption.decrypt(invite.getTokenEncrypted());
    }

    /**
     * Withdraws an invite.
     *
     * <p>Scoped by household id, not by invite id alone: an admin of one household must not be
     * able to revoke another household's invite by guessing a UUID. Under one-household-per-install
     * that is theoretical today and structural tomorrow, which is exactly when it is cheap to get
     * right (§9 risk 2).
     */
    @Transactional
    public void revoke(UUID householdId, UUID inviteId, UUID actorId, Instant now) {
        HouseholdInvite invite = invites.findByIdAndHouseholdId(inviteId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Invite", inviteId));
        invite.revoke(now);
        invites.save(invite);
        LOG.info("Invite {} revoked by {}", inviteId, actorId);
    }

    /**
     * The public preview behind {@code GET /v1/auth/invites/{token}}.
     *
     * <p>Always 200. A 404 for an unknown token would turn this into an oracle for guessing token
     * values, and the page that calls it has nothing better to do with a 404 than render the same
     * copy it renders for {@code valid: false}.
     */
    @Transactional(readOnly = true)
    public InvitePreviewResponse preview(String rawToken, Instant now) {
        if (rawToken == null || rawToken.isBlank()) {
            return InvitePreviewResponse.invalid();
        }
        return invites.findByTokenHash(hash(rawToken))
            .filter(invite -> invite.isUsableAt(now))
            .map(invite -> new InvitePreviewResponse(true, invite.getEmail(),
                invite.getExpiresAt()))
            .orElseGet(InvitePreviewResponse::invalid);
    }

    /**
     * Resolves a token at registration time.
     *
     * @throws InviteException 404 for unknown/revoked/wrong-address, 410 for expired or already
     *                         redeemed — the statuses the clients dispatch this family on
     */
    @Transactional
    public HouseholdInvite resolveForRegistration(String rawToken, String email, Instant now) {
        // Locked, not merely read: this is a check-then-act on a single-use credential, and the
        // caller writes accepted_at only after hashing a password — tens of milliseconds of
        // argon2id later. Without the lock two registrations posting the same token concurrently
        // both saw accepted_at = null and both succeeded, and a one-seat invite admitted two
        // people. Not readOnly for the same reason: a read-only transaction cannot hold a write
        // lock, and the caller's accept() must land in this same transaction.
        HouseholdInvite invite = invites.findByTokenHashForUpdate(hash(rawToken))
            .orElseThrow(InviteException::invalid);
        if (invite.getRevokedAt() != null) {
            throw InviteException.invalid();
        }
        if (invite.getAcceptedAt() != null) {
            throw InviteException.alreadyUsed();
        }
        if (!invite.getExpiresAt().isAfter(now)) {
            throw InviteException.expired();
        }
        if (invite.getEmail() != null && !invite.getEmail().equalsIgnoreCase(normalise(email))) {
            throw InviteException.wrongEmail();
        }
        return invite;
    }

    /** Marks an invite redeemed. Called inside the registration transaction. */
    @Transactional
    public void markAccepted(HouseholdInvite invite, UUID newUserId, Instant now) {
        invite.accept(newUserId, now);
        invites.save(invite);
        // After commit, not inside it: the user this row points at was inserted by the caller's
        // still-open transaction, so a REQUIRES_NEW write here cannot see it and the foreign key
        // to users fails. The recorder swallows database errors by design, so the row would
        // simply never appear (see SecurityEventRecorder#recordAfterCommit).
        securityEvents.recordAfterCommit(SecurityEventType.INVITE_ACCEPTED, newUserId, newUserId,
            Map.of("inviteId", invite.getId().toString(),
                "householdId", invite.getHouseholdId().toString()));
    }

    /** Daily sweep (§5.6 {@code invite-expiry-sweep}); collapse step 4 schedules it. */
    @Transactional
    public int purgeExpired(Instant cutoff) {
        return invites.purgeExpiredBefore(cutoff);
    }

    private InviteResponse toResponse(HouseholdInvite invite, String rawToken, String createdBy) {
        return new InviteResponse(
            invite.getId(),
            invite.getEmail(),
            rawToken,
            rawToken == null ? null : properties.inviteUrl(rawToken),
            invite.getExpiresAt(),
            invite.getCreatedAt(),
            invite.getAcceptedAt(),
            createdBy);
    }

    private String actorEmail(UUID actorId) {
        return actorId == null
            ? null
            : users.findAccount(actorId).map(User::getEmail).orElse(null);
    }

    private static String normalise(String email) {
        return email == null || email.isBlank() ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex; matches the {@code CHAR(64)} column of V9.1. */
    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JRE specification", e);
        }
    }

    /** Test seam: the role an invite confers, without exposing the entity to a controller. */
    public static UserRole roleOf(Optional<HouseholdInvite> invite) {
        return invite.map(HouseholdInvite::getRole).orElse(UserRole.MEMBER);
    }
}
