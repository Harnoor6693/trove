package finance.trove.accounts;

import finance.trove.common.plugin.SourceKind;
import finance.trove.common.repository.UserScopedRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link ConnectedAccount}.
 *
 * <p>Extends {@link UserScopedRepository}, so it inherits no {@code findById} and no
 * {@code findAll}: every query here takes a {@code householdId}, which is what makes the G1 IDOR
 * a compile error rather than a review question ({@code docs/recovery/06-COLLAPSE-PLAN.md}
 * §9 risk 2).
 */
public interface ConnectedAccountRepository extends UserScopedRepository<ConnectedAccount, UUID> {

    /** The household's accounts, newest first. Bounded by construction — a household has few. */
    List<ConnectedAccount> findAllByHouseholdIdOrderByCreatedAtDesc(UUID householdId);

    /**
     * The upsert lookup step 7 uses on a re-exchange or a repeat upload (§6 M0). Scoped, and
     * named for what it is rather than reusing one of the banned unscoped finder names.
     */
    Optional<ConnectedAccount> findByHouseholdIdAndSourceKindAndExternalAccountId(
        UUID householdId, SourceKind sourceKind, String externalAccountId);

    /**
     * Saves and flushes in one call, so that a uniqueness violation is raised while the caller is
     * still inside its own {@code try}.
     *
     * <p>This is not a stylistic preference. {@code save()} on a new entity is
     * {@code EntityManager.persist}, and this entity's {@code @GeneratedValue} UUID is produced in
     * memory, so the INSERT is not issued until the persistence context flushes — which, for a
     * method that runs no further query, happens at COMMIT, after the method body and its
     * {@code catch} clauses have already been left. A {@code try/catch} around a bare
     * {@code save()} therefore catches nothing, and the specific 409 the caller means to raise
     * would be replaced by whatever the global handler makes of the escaping exception.
     *
     * <p>Declared here rather than on {@link UserScopedRepository} because flushing is a choice a
     * caller makes for a reason, and the reason is local to {@link AccountService#create}.
     */
    <S extends ConnectedAccount> S saveAndFlush(S entity);

    /**
     * Takes the sync claim (§6 M0): sets {@code sync_status} to {@code SYNCING} only if it is not
     * already, and reports how many rows that changed.
     *
     * <p><strong>Zero rows updated means somebody else holds the claim</strong>, which the caller
     * turns into 409. The check and the write are one statement precisely so that two overlapping
     * triggers cannot both read {@code OK}, both decide to proceed, and then collide on the unique
     * dedup index halfway through the batch. An in-memory "is it already syncing?" check cannot
     * give that guarantee at any isolation level.
     *
     * <p>The claim is a lease: a {@code SYNCING} row not touched for an hour belongs to a run that
     * crashed (JVM kill, deploy) and may be taken over, otherwise the account would answer 409
     * forever. An hour is far longer than the longest legitimate commit.
     *
     * @return 1 when this caller took the claim, 0 when another already holds it or the id is not
     *         this household's — which are indistinguishable here on purpose, because the caller
     *         has already established the account exists
     */
    @Modifying
    @Query(value = "UPDATE connected_account SET sync_status = 'SYNCING', updated_at = now() "
        + "WHERE id = :id AND household_id = :householdId AND (sync_status <> 'SYNCING' "
        + "OR updated_at < now() - interval '1 hour')",
        nativeQuery = true)
    int claimForSync(@Param("id") UUID id, @Param("householdId") UUID householdId);

    /**
     * Releases the claim and records the outcome, in one statement, for the same reason.
     *
     * <p>Native rather than a load-modify-save because it must run in its own short transaction on
     * every path out of the sync — including the one where an exception has already left the
     * caller's state uncertain — and re-reading the entity there would risk re-persisting whatever
     * a partially applied batch had left in the persistence context.
     *
     * @param lastSyncedAt {@code true} to stamp {@code last_synced_at} with the current instant,
     *                     which only a run that actually <em>stored</em> what it read may do — a
     *                     fetch that succeeded and a batch that then rolled back kept nothing
     */
    @Modifying
    @Query(value = "UPDATE connected_account SET sync_status = :status, "
        + "error_message = :errorMessage, updated_at = now(), "
        + "last_synced_at = CASE WHEN :lastSyncedAt THEN now() ELSE last_synced_at END "
        + "WHERE id = :id AND household_id = :householdId", nativeQuery = true)
    int releaseClaim(@Param("id") UUID id, @Param("householdId") UUID householdId,
                     @Param("status") String status, @Param("errorMessage") String errorMessage,
                     @Param("lastSyncedAt") boolean lastSyncedAt);
}
