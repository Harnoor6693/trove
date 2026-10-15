package finance.trove.households.invites;

import finance.trove.common.repository.UserScopedRepository;
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
 * Invitations belong to a household, so this extends {@link UserScopedRepository}: the admin
 * routes can only list, and can only revoke, invitations of the caller's own household. An admin
 * of household A cannot revoke household B's invitation by guessing its id — the finder simply
 * has no form that would let them try.
 *
 * <p>{@link #findByTokenHash} is the one unscoped lookup, and it must be: redeeming an invitation
 * happens before any household is known — that is what the invitation is <em>for</em>.
 */
public interface HouseholdInviteRepository
    extends UserScopedRepository<HouseholdInvite, UUID> {

    Optional<HouseholdInvite> findByTokenHash(String tokenHash);

    /**
     * The redemption lookup, holding a row lock for the rest of the transaction.
     *
     * <p>An invite is single-use, and "single" was previously enforced by reading
     * {@code accepted_at} and later writing it — a check-then-act with nothing between the two.
     * Two registrations posting the same token at the same moment both read {@code null} and both
     * succeeded, so a one-seat invite let two strangers into the household. The same lock, for
     * the same reason, is on {@code RefreshTokenRepository#findByTokenHashForUpdate}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from HouseholdInvite i where i.tokenHash = :tokenHash")
    Optional<HouseholdInvite> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    List<HouseholdInvite> findAllByHouseholdIdOrderByCreatedAtDesc(UUID householdId);

    /** Daily sweep (§5.6 {@code invite-expiry-sweep}); collapse step 4 schedules it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from HouseholdInvite i where i.expiresAt < :cutoff and i.acceptedAt is null")
    int purgeExpiredBefore(@Param("cutoff") Instant cutoff);
}
