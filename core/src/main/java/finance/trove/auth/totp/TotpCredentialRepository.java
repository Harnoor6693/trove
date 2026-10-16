package finance.trove.auth.totp;

import finance.trove.common.repository.UserKeyedRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * A second factor belongs to one member and to nobody else — not even to their household's
 * admin, who may <em>clear</em> a member's TOTP (an audited action) but can never read the
 * secret. Hence {@link UserKeyedRepository}, whose finders all take a user id.
 *
 * <p>The two spends are conditional {@code UPDATE}s rather than read-modify-save, because "used
 * once" has to hold when two logins present the same code at the same moment. Read and saved
 * through the entity, both requests saw the code as unused and both went through. Each statement
 * here re-checks its condition against the row version it locks, so of two concurrent spends
 * exactly one matches, and the caller is told which by the row count.
 */
public interface TotpCredentialRepository extends UserKeyedRepository<TotpCredential> {

    /**
     * Marks {@code step} used, unless it or a later step already is.
     *
     * @return {@code 1} if this call spent the step, {@code 0} if it was already spent
     */
    @Modifying(flushAutomatically = true)
    @Query("update TotpCredential c set c.lastUsedStep = :step where c.userId = :userId "
        + "and c.enabled = true and (c.lastUsedStep is null or c.lastUsedStep < :step)")
    int claimStep(@Param("userId") UUID userId, @Param("step") long step);

    /**
     * Removes one recovery code by its hash, if it is still there.
     *
     * @return {@code 1} if this call spent the code, {@code 0} if it was unknown or already spent
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "UPDATE totp_credential "
        + "SET recovery_codes_hashed = array_remove(recovery_codes_hashed, CAST(:hash AS text)) "
        + "WHERE user_id = :userId AND enabled "
        + "AND CAST(:hash AS text) = ANY(recovery_codes_hashed)", nativeQuery = true)
    int consumeRecoveryCode(@Param("userId") UUID userId, @Param("hash") String hash);
}
