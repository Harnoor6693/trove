package finance.trove.auth.reset;

import finance.trove.common.repository.UserOwnedRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Reset tokens are per-user, never per-household: a household admin issues one, but the token
 * belongs to the member it resets.
 *
 * <p>{@link #findByTokenHash} is the one unscoped finder, and it cannot be otherwise — the redeem
 * endpoint is anonymous, so the token <em>is</em> the identity claim. It is constrained by a
 * unique index on a 64-character hash, which is a tighter filter than a user id would be.
 */
public interface PasswordResetTokenRepository
    extends UserOwnedRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Invalidates every outstanding link for one account.
     *
     * <p>Called when a new one is issued and again when one is redeemed. Without it, an admin who
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
