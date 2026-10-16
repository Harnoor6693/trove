package finance.trove.auth.reset;

import finance.trove.common.repository.UserOwnedRepository;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reset tokens are per-user, never per-household: a household admin issues one, but the token
 * belongs to the member it resets.
 *
 * <p>{@link #findByTokenHashForUpdate} is the one unscoped finder, and it cannot be otherwise —
 * the redeem endpoint is anonymous, so the token <em>is</em> the identity claim. It is constrained
 * by a unique index on a 64-character hash, which is a tighter filter than a user id would be.
 */
public interface PasswordResetTokenRepository
    extends UserOwnedRepository<PasswordResetToken, UUID> {

    /**
     * The redemption lookup, holding the row lock for the rest of the transaction.
     *
     * <p>A link is single-use, and "single" used to be enforced by reading {@code consumed_at}
     * and writing it after an argon2id hash — check-then-act with nothing between the two. Two
     * redeems of one link both read {@code null}, both set a password, and whichever committed
     * last chose it, so an attacker racing the owner could win. With the lock the second redeem
     * waits, then reads the row the first one consumed. The same lock, for the same reason, as
     * {@code HouseholdInviteRepository#findByTokenHashForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Invalidates every outstanding link for one account.
     *
     * <p>Called when a new link is issued; redeeming consumes only the redeemed token. Without it,
     * an admin who
     * generates a second link because the first was mistyped leaves the first one live for the
     * rest of its hour — two valid resets for one account, only one of which anybody is watching.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetToken t set t.consumedAt = :now "
        + "where t.userId = :userId and t.consumedAt is null")
    int consumeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PasswordResetToken t where t.expiresAt < :cutoff")
    int purgeExpiredBefore(@Param("cutoff") Instant cutoff);
}
