package finance.trove.auth.session;

import finance.trove.common.repository.UserOwnedRepository;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Sessions are per-user, never per-household (§1.5): a household admin administers a member's
 * account but does not inherit their sessions, so this extends {@link UserOwnedRepository}.
 *
 * <p>{@link #findByTokenHash} is the one finder that is not user-scoped, and it cannot be: the
 * refresh endpoint is anonymous — the token <em>is</em> the identity claim. It is scoped by a
 * unique index on a SHA-256 hash instead, which is a stronger constraint than a user id would be.
 */
public interface RefreshTokenRepository extends UserOwnedRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * The same lookup, holding a row lock for the rest of the transaction.
     *
     * <p>Used only by rotation, and it is what makes two tabs refreshing at the same instant
     * <em>deterministic</em> rather than merely usually fine. Without the lock, both requests read
     * the row before either commits, both see it un-rotated, and both mint a successor: the family
     * forks, the two tabs hold different tokens, and whichever one is presented second later looks
     * like a replay and revokes the family. The grace window cannot help, because there is no
     * single successor to hand back.
     *
     * <p>With the lock the second caller waits, then reads the row as rotated and takes the grace
     * path — one successor, both callers, no fork. The wait is microseconds; the alternative is a
     * household logged out for opening two tabs.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    Optional<RefreshToken> findByIdAndUserId(UUID id, UUID userId);

    List<RefreshToken> findAllByFamilyId(UUID familyId);

    /**
     * Whether a family still has an unrevoked link — the question every authenticated request
     * asks about the {@code fid} claim on its access token (see {@code SessionValidity}).
     *
     * <p>Rotation leaves the predecessor un-revoked (it is <em>rotated</em>, a different state),
     * so a live family always has at least one such row. Revoking a family sets {@code revoked_at}
     * on all of them in one statement, and deleting a user takes the rows with it, so both a
     * revoked session and a deleted account answer false. Served by
     * {@code refresh_token_family_id_idx}.
     */
    boolean existsByFamilyIdAndRevokedAtIsNull(UUID familyId);

    List<RefreshToken> findAllByUserIdAndRevokedAtIsNullAndExpiresAtAfter(UUID userId,
                                                                         Instant now);

    /**
     * Revokes every unrevoked token in a family in one statement.
     *
     * <p>A row-by-row loop would leave a window in which a concurrent refresh could rotate a link
     * that was about to be revoked — which is precisely the race the reuse detection exists to
     * close.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now "
        + "where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now "
        + "where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * Ends every session for a user <em>except</em> one named family.
     *
     * <p>What a password change actually wants (§4.1: "revokes all other sessions"). Revoking the
     * caller's own family too would be defensible on the server and wrong in practice: the shipped
     * mobile client discards the replacement session this endpoint returns
     * ({@code apps/mobile/src/api/auth.ts:76} — {@code Promise<void>}) and treats a rejected
     * refresh as terminal ({@code apps/mobile/src/api/client.ts:255}), so it would sign the user
     * out within fifteen minutes of them changing their password.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now "
        + "where t.userId = :userId and t.familyId <> :keepFamilyId and t.revokedAt is null")
    int revokeAllForUserExcept(@Param("userId") UUID userId,
                               @Param("keepFamilyId") UUID keepFamilyId,
                               @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    int purgeExpired(@Param("cutoff") Instant cutoff);
}
