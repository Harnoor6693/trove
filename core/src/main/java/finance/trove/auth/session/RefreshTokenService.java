package finance.trove.auth.session;

import finance.trove.app.audit.SecurityEventRecorder;
import finance.trove.app.audit.SecurityEventType;
import finance.trove.auth.jwt.IssuedToken;
import finance.trove.auth.jwt.JwtIssuerService;
import finance.trove.auth.jwt.JwtProperties;
import finance.trove.auth.jwt.JwtVerifier;
import finance.trove.auth.jwt.TokenType;
import finance.trove.auth.jwt.VerifiedToken;
import finance.trove.common.cache.ExpiringCache;
import finance.trove.common.exception.UnauthorizedException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues, rotates and revokes refresh tokens ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5).
 *
 * <p>The state machine, in the order the checks are made, because the order is the design:
 * <ol>
 *   <li><strong>Signature and {@code typ=refresh}.</strong> Cheap, and it means an access token
 *       presented here is refused before any database work happens.</li>
 *   <li><strong>Unknown hash → 401.</strong> A token this install never issued, or one already
 *       purged.</li>
 *   <li><strong>Already rotated?</strong> Two sub-cases, and telling them apart is the whole
 *       point of the grace window:
 *       <ul>
 *         <li>inside {@link RefreshProperties#reuseGrace} of the successor's issue — this is two
 *             browser tabs, or one retried request. Hand back the <em>same</em> successor. The
 *             family is untouched.</li>
 *         <li>outside it — nobody honest still holds this token. Revoke the whole family, record
 *             {@code REFRESH_REUSE_DETECTED}, refuse.</li>
 *       </ul>
 *       Without the first case, ordinary concurrent tab usage force-logs-out the household; both
 *       adversarial reviewers found that independently (Client-Contract 13, Security 7).</li>
 *   <li><strong>Explicitly revoked → 401</strong> (a logout, or a family already burnt).</li>
 *   <li><strong>Past the absolute deadline, or idle too long → 401</strong> and the family is
 *       closed.</li>
 *   <li><strong>Rotate.</strong> The successor inherits the family's deadline unchanged, so
 *       refreshing forever cannot outlive the ceiling.</li>
 * </ol>
 *
 * <p>The grace cache holds a successor token in memory for thirty seconds. That is deliberate and
 * bounded: a plaintext refresh token must never be written to a database, a log or a disk, and the
 * monolith is one JVM — the reason Valkey could be dropped in the first place (§1.1). A restart
 * empties it; a replay inside the window that then finds no successor is refused without
 * revoking the family, because the window is read from the row as well as from the cache.
 */
@Service
public class RefreshTokenService {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int GRACE_CACHE_MAX = 2_000;

    private final RefreshTokenRepository tokens;
    private final JwtIssuerService issuer;
    private final JwtVerifier verifier;
    private final JwtProperties jwtProperties;
    private final RefreshProperties properties;
    private final SecurityEventRecorder securityEvents;
    private final SessionRevoker revoker;
    private final ExpiringCache<String, GraceEntry> graceCache;

    /**
     * A rotation held briefly so a replay of its predecessor can be answered with the same
     * successor instead of a family revoke.
     *
     * <p>{@code rotatedAt} is stored rather than relying on the cache's own eviction clock. The
     * cache expires entries against the wall clock; every decision in this service is made against
     * the {@code now} the caller passes in. Mixing the two means the window's width depends on
     * which clock you ask, and it made the "outside the window" branch untestable — a test that
     * advanced its own clock by two minutes still landed inside a cache entry that was a
     * millisecond old. The cache TTL now bounds memory only; this field decides the behaviour.
     */
    private record GraceEntry(IssuedToken successor, Instant rotatedAt) {
    }

    public RefreshTokenService(RefreshTokenRepository tokens, JwtIssuerService issuer,
                               JwtVerifier verifier, JwtProperties jwtProperties,
                               RefreshProperties properties,
                               SecurityEventRecorder securityEvents,
                               SessionRevoker revoker) {
        this.tokens = tokens;
        this.revoker = revoker;
        this.issuer = issuer;
        this.verifier = verifier;
        this.jwtProperties = jwtProperties;
        this.properties = properties;
        this.securityEvents = securityEvents;
        // ponytail: one JVM's memory. A restart or an eviction loses the successor, and the
        // racing tab is then refused rather than served (rotate). Fine for the single-instance
        // monolith; a second instance needs the successor kept for reuseGrace where every
        // instance can read it — encrypted on the rotated row, never in plaintext (B3-13).
        this.graceCache = new ExpiringCache<>(GRACE_CACHE_MAX, properties.reuseGrace());
    }

    /** Starts a new family — one per login. */
    @Transactional
    public RefreshResult issue(UUID userId, String ip, String userAgent, Instant now) {
        UUID familyId = UUID.randomUUID();
        IssuedToken token = issuer.issueRefreshToken(userId, now, jwtProperties.refreshTtl());
        tokens.save(new RefreshToken(userId, hash(token.value()), familyId, token.expiresAt(),
            now, ip, truncate(userAgent)));
        return new RefreshResult(userId, familyId, token, false);
    }

    /**
     * Exchanges a refresh token for its successor.
     *
     * @throws UnauthorizedException for every refusal, with the same slug and message, for the
     *                               same reason logins are enumeration-safe: the caller must not
     *                               learn whether a token was unknown, revoked, expired or
     *                               replayed
     */
    @Transactional
    public RefreshResult rotate(String rawToken, String ip, String userAgent, Instant now) {
        VerifiedToken verified;
        try {
            verified = verifier.verify(rawToken, TokenType.REFRESH);
        } catch (UnauthorizedException e) {
            // Re-worded, not re-thrown as-is: the verifier says "token" and everything below says
            // "session", and a caller that can tell a malformed token from an unknown one has a
            // free oracle for which half of a guess was wrong.
            throw new UnauthorizedException("Invalid or expired session");
        }
        String tokenHash = hash(rawToken);

        // Locked, not merely read: two tabs refreshing at the same instant must converge on one
        // successor rather than forking the family. See findByTokenHashForUpdate.
        RefreshToken stored = tokens.findByTokenHashForUpdate(tokenHash)
            .orElseThrow(() -> new UnauthorizedException("Invalid or expired session"));

        if (stored.isRotated()) {
            GraceEntry cached = graceCache.peek(tokenHash);
            if (cached != null && withinGraceWindow(cached, now)) {
                LOG.debug("Refresh replayed inside the grace window; returning the successor");
                return new RefreshResult(stored.getUserId(), stored.getFamilyId(),
                    cached.successor(), true);
            }
            if (cached == null && withinGrace(stored.getRevokedAt(), now)) {
                // The race the window exists for, but the successor is no longer in memory: this
                // process restarted, or the cache evicted it, since the rotation. It cannot be
                // handed back, so the request is refused — and only refused. Burning the family
                // here signed a member out of every device whenever a deploy landed inside the
                // window (B3-13), and inside the window a replay is not treated as theft anyway.
                LOG.info("Refresh replayed inside the grace window after its successor left "
                    + "memory; refused without revoking family {}", stored.getFamilyId());
                throw new UnauthorizedException("Invalid or expired session");
            }
            revokeFamilyOnReuse(stored, now);
            throw new UnauthorizedException("Invalid or expired session");
        }

        if (stored.isRevoked() || stored.isExpiredAt(now) || isIdle(stored, now)) {
            // In its own transaction: the throw below would otherwise roll this back with it.
            revoker.revokeFamilyNow(stored.getFamilyId(), now);
            throw new UnauthorizedException("Invalid or expired session");
        }

        Duration remaining = Duration.between(now, stored.getExpiresAt());
        IssuedToken successorToken =
            issuer.issueRefreshToken(verified.userId(), now, remaining);
        RefreshToken successor = new RefreshToken(stored.getUserId(), hash(successorToken.value()),
            stored.getFamilyId(), stored.getExpiresAt(), now, ip, truncate(userAgent));
        tokens.save(successor);
        stored.replaceWith(successor.getId(), now);
        tokens.save(stored);
        graceCache.put(tokenHash, new GraceEntry(successorToken, now));

        return new RefreshResult(stored.getUserId(), stored.getFamilyId(), successorToken, false);
    }

    /** Ends the family this token belongs to. Logout is idempotent: an unknown token is a no-op. */
    @Transactional
    public void revokeByToken(String rawToken, Instant now) {
        Optional<RefreshToken> stored = tokens.findByTokenHash(hash(rawToken));
        stored.ifPresent(token -> {
            tokens.revokeFamily(token.getFamilyId(), now);
            securityEvents.record(SecurityEventType.SESSION_REVOKE, token.getUserId(),
                Map.of("reason", "logout"));
        });
    }

    /** Ends one named session. Scoped to the caller: a family id alone authorises nothing. */
    @Transactional
    public boolean revokeFamily(UUID userId, UUID familyId, Instant now) {
        List<RefreshToken> family = tokens.findAllByFamilyId(familyId);
        boolean ownedByCaller = !family.isEmpty()
            && family.stream().allMatch(t -> t.getUserId().equals(userId));
        if (!ownedByCaller) {
            return false;
        }
        tokens.revokeFamily(familyId, now);
        securityEvents.record(SecurityEventType.SESSION_REVOKE, userId,
            Map.of("reason", "user-revoked-session"));
        return true;
    }

    /** Ends every session for a user — after a password change or reset (§1.5). */
    @Transactional
    public int revokeAllForUser(UUID userId, String reason, Instant now) {
        int revoked = tokens.revokeAllForUser(userId, now);
        if (revoked > 0) {
            securityEvents.record(SecurityEventType.SESSION_REVOKE, userId,
                Map.of("reason", reason, "sessions", revoked));
        }
        return revoked;
    }

    /**
     * Ends every session except the caller's own (§4.1).
     *
     * @param keepFamilyId the family to spare, or {@code null} to spare none — a token minted
     *                     before the {@code fid} claim existed has no family to name, and the
     *                     safe reading of "revoke the others" when the others cannot be
     *                     distinguished is to revoke all of them
     */
    @Transactional
    public int revokeOtherSessions(UUID userId, UUID keepFamilyId, String reason, Instant now) {
        int revoked = keepFamilyId == null
            ? tokens.revokeAllForUser(userId, now)
            : tokens.revokeAllForUserExcept(userId, keepFamilyId, now);
        if (revoked > 0) {
            securityEvents.record(SecurityEventType.SESSION_REVOKE, userId,
                Map.of("reason", reason, "sessions", revoked,
                    "keptCurrent", keepFamilyId != null));
        }
        return revoked;
    }

    /** Live sessions, one row per family, newest first. */
    @Transactional(readOnly = true)
    public List<SessionSummary> listSessions(UUID userId, UUID currentFamilyId, Instant now) {
        return tokens.findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(userId, now).stream()
            .collect(java.util.stream.Collectors.groupingBy(RefreshToken::getFamilyId))
            .entrySet().stream()
            .map(entry -> {
                List<RefreshToken> family = entry.getValue();
                RefreshToken newest = family.stream()
                    .max(Comparator.comparing(RefreshToken::getIssuedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElseThrow();
                RefreshToken oldest = family.stream()
                    .min(Comparator.comparing(RefreshToken::getIssuedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElseThrow();
                return new SessionSummary(entry.getKey(), oldest.getIssuedAt(),
                    newest.getLastUsedAt(), newest.getExpiresAt(), newest.getIpAddress(),
                    newest.getUserAgent(), entry.getKey().equals(currentFamilyId));
            })
            .sorted(Comparator.comparing(SessionSummary::createdAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    /** The family a raw token belongs to, for marking the caller's own session as current. */
    @Transactional(readOnly = true)
    public Optional<UUID> familyOf(String rawToken) {
        return tokens.findByTokenHash(hash(rawToken)).map(RefreshToken::getFamilyId);
    }

    /**
     * Daily purge, run by {@code PurgeJobs#purgeRefreshTokens} at 03:10 UTC (§5.6
     * {@code refresh-token-purge}).
     */
    @Transactional
    public int purgeExpired(Instant cutoff) {
        return tokens.purgeExpired(cutoff);
    }

    /**
     * Burns the family, in a transaction that survives the refusal.
     *
     * <p>{@link SessionRevoker} rather than {@code tokens.revokeFamily} directly: this method's
     * caller throws immediately afterwards, which rolls back the current transaction. Doing the
     * revocation inline left the family live while every log line and audit row said it had been
     * revoked.
     */
    private void revokeFamilyOnReuse(RefreshToken stored, Instant now) {
        int revoked = revoker.revokeFamilyNow(stored.getFamilyId(), now);
        LOG.warn("Refresh-token reuse detected outside the grace window; revoked {} tokens "
            + "in family {}", revoked, stored.getFamilyId());
        securityEvents.record(SecurityEventType.REFRESH_REUSE_DETECTED, stored.getUserId(),
            Map.of("familyId", stored.getFamilyId().toString(), "revoked", revoked));
    }

    /**
     * Judged on the caller's clock, and on a successor that has not itself expired.
     *
     * <p>The comparison is on the <strong>absolute</strong> difference, and that is not sloppiness.
     * Each request stamps its own {@code now} when it enters the controller, but the row lock
     * serialises the database work — so of two tabs refreshing at once, the one that entered
     * second can perfectly well be the one that rotates first, leaving the other with a
     * {@code now} a few milliseconds <em>earlier</em> than the rotation it is being measured
     * against. Treating that negative difference as "outside the window" revoked the family for
     * two tabs behaving exactly as the window exists to tolerate, and it did so intermittently,
     * under load, which is the worst way to find out. The clock here is the server's own, so the
     * difference can only ever be request interleaving; a symmetric bound covers it without
     * widening the window a real replay would have to fit through.
     */
    private boolean withinGraceWindow(GraceEntry cached, Instant now) {
        return withinGrace(cached.rotatedAt(), now) && cached.successor().expiresAt().isAfter(now);
    }

    /** See {@link #withinGraceWindow}; {@code rotatedAt} is the row's {@code revoked_at}. */
    private boolean withinGrace(Instant rotatedAt, Instant now) {
        return rotatedAt != null
            && Duration.between(rotatedAt, now).abs().compareTo(properties.reuseGrace()) <= 0;
    }

    private boolean isIdle(RefreshToken stored, Instant now) {
        Instant lastUsed = stored.getLastUsedAt();
        return lastUsed != null
            && Duration.between(lastUsed, now).compareTo(properties.idleTimeout()) > 0;
    }

    private static String truncate(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return null;
        }
        return userAgent.length() <= 500 ? userAgent : userAgent.substring(0, 500);
    }

    /** SHA-256 hex. The token itself never reaches the database (§1.5). */
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
