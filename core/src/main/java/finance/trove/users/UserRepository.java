package finance.trove.users;

import finance.trove.common.repository.UserScopedRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Members of a household ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risk 2).
 *
 * <p>Extending {@link UserScopedRepository} is what makes the admin routes tenant-safe by
 * construction: {@code GET /v1/admin/users} and {@code DELETE /v1/admin/users/{id}} can only
 * reach rows through {@code findAllByHouseholdId} / {@code findByIdAndHouseholdId}, so an admin
 * of household A cannot enumerate or delete a member of household B even if they guess the id.
 * The base does not inherit {@code findById} at all, and {@code ArchitectureTest} fails the build
 * if a subinterface declares one.
 *
 * <p>The three install-wide queries below are deliberate exceptions, each justified:
 * <ul>
 *   <li>{@link #findByEmailIgnoreCase} — email is globally unique on this install, and login has
 *       to resolve an address to a user <em>before</em> any household is known. It is the one
 *       place a lookup precedes tenancy, which is exactly why the login response is
 *       enumeration-safe.</li>
 *   <li>{@link #countAllUsers} — {@code FirstRunBootstrap} asks "is this a fresh install?" and
 *       there is no household yet to scope the question to.</li>
 *   <li>{@link #countByHouseholdIdAndRoleAndActiveTrue} — the last-ADMIN guard.</li>
 * </ul>
 */
public interface UserRepository extends UserScopedRepository<User, UUID> {

    /**
     * Case-insensitive lookup on the {@code users_email_lower_idx} unique index.
     *
     * <p>Callers must not branch their <em>response</em> on whether this returns a value: an
     * unknown address and a wrong password produce the same status, body and (via a dummy hash)
     * roughly the same latency, or the login form becomes an account-enumeration oracle
     * (gap P2-4).
     */
    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findAllByHouseholdIdOrderByCreatedAtAsc(UUID householdId);

    /**
     * The one by-id lookup that is not household-scoped, for the two callers that provably have
     * no household to scope by: {@code PasswordResetService#redeem}, whose caller is anonymous
     * and holds only a token, and {@code FirstRunBootstrap}, which runs before any household
     * membership exists.
     *
     * <p>It is named {@code findAccount} rather than {@code findById} deliberately. The
     * {@code findById} name is the one {@code ArchitectureTest} bans on a scoped repository, and
     * a method that quietly re-introduced it would hand every later step an unscoped finder to
     * reach for by accident (§9 risk 2). A distinct name makes each use a decision.
     *
     * <p>{@code users} is also the one table where an unscoped read is not a tenancy leak in the
     * first place: the household is a column ON this row, so filtering by it would be circular.
     * Every ledger table is a different matter, and the ban stands there.
     */
    @Query("select u from User u where u.id = :id")
    Optional<User> findAccount(@Param("id") UUID id);

    @Query("select count(u) from User u")
    long countAllUsers();

    /**
     * The last-ADMIN guard's count, and it counts <strong>ACTIVE</strong> administrators only.
     *
     * <p>Since collapse step 18 a deleted member's row survives the deletion grace, so the older
     * {@code countByHouseholdIdAndRole} would count an administrator who has already left: a
     * household with one live admin and one soft-deleted one would read two, the guard would step
     * aside, and deleting the live one would leave the household with nobody who can sign in and
     * administer it — the exact failure the guard exists to prevent, reintroduced by the soft
     * delete. {@code is_active} is the liveness flag every other check in the tree already reads,
     * so it is the one this counts on.
     */
    long countByHouseholdIdAndRoleAndActiveTrue(UUID householdId, UserRole role);

    @Query("select u.timezone from User u where u.id = :id")
    Optional<String> findTimezoneById(@Param("id") UUID id);

    /**
     * Erases one account, but ONLY while it is still past its restore window — the purge job's
     * one write (collapse step 18).
     *
     * <p><strong>The predicate is the whole point and it is not decoration.</strong> The job
     * SELECTs a batch of candidates and then works through it, deleting each member's export
     * archives off the volume before erasing the row, so several seconds of filesystem work can
     * separate the read that chose an account from the write that erases it. An administrator
     * calling {@code POST /v1/admin/users/{id}/restore} inside that window commits
     * {@code is_active = true, deleted_at = null} — and an unconditional
     * {@code deleteByIdAndHouseholdId} taken from the stale batch would then erase an account
     * that had just been brought back. The edge of the grace is exactly when an administrator is
     * most likely to restore somebody and exactly when this job runs, so the two really do meet.
     *
     * <p>Re-stating the condition in the DELETE closes it with no lock and no coordination:
     * Postgres re-evaluates the WHERE clause against the row version it actually locks, so a
     * concurrent restore either commits first (and the DELETE matches nothing, returning 0) or
     * commits after (and finds no row). {@code restoreMember} takes the household lock for its
     * own reasons; this job deliberately takes none, because a scheduled sweep must not queue
     * behind interactive traffic.
     *
     * <p>{@code @Transactional} is not optional. Spring Data runs a repository query method in
     * {@code SimpleJpaRepository}'s class-level read-only transaction unless told otherwise, and
     * Postgres refuses a DELETE in a read-only transaction. {@code @Modifying} is what makes this
     * a statement rather than a query. The cascades are the database's: §3's foreign keys already
     * say what belongs to a member.
     *
     * @return rows erased: {@code 0} when the account was restored in the meantime, when its
     *         stamp is gone, or when the id belongs to another household
     */
    @Transactional
    @Modifying
    @Query("delete from User u where u.id = :id and u.householdId = :householdId "
        + "and u.deletedAt is not null and u.deletedAt < :cutoff")
    int eraseAccountPastItsGrace(@Param("id") UUID id,
                                 @Param("householdId") UUID householdId,
                                 @Param("cutoff") Instant cutoff);

    /**
     * Accounts whose deletion grace has run out — the purge job's only query (collapse step 18).
     *
     * <p>Deliberately NOT household-scoped, and deliberately not named {@code findAll...}: it runs
     * on a schedule with no caller, no session and no household to scope by, and the rows it
     * returns are never rendered to anybody — each one is erased. It selects on
     * {@code deleted_at} alone, which is exactly what that column exists for: a member an
     * administrator merely deactivated has a null stamp and is never touched here, however long
     * they stay disabled.
     *
     * <p>The {@code Pageable} bounds one pass, so an install with a long backlog is drained over
     * several runs rather than in one transaction that erases hundreds of accounts at once.
     */
    @Query("select u from User u where u.deletedAt is not null and u.deletedAt < :cutoff "
        + "order by u.deletedAt asc")
    List<User> findPurgeCandidates(@Param("cutoff") Instant cutoff, Pageable page);
}
