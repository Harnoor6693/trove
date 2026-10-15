package finance.trove.households;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Repository over the tenancy root itself.
 *
 * <p>It deliberately does <em>not</em> extend {@code UserScopedRepository}: that base exists to
 * force a {@code householdId} parameter onto every query over a household-<em>owned</em> table,
 * and this table is the household. The id passed to {@link #findById} always comes from the
 * signed token's {@code hid} claim (never from a path variable or a body), so the scoping check
 * has already happened by the time a call gets here — {@code AccessControlIT} is the standing
 * proof of that.
 */
public interface HouseholdRepository extends Repository<Household, UUID> {

    Optional<Household> findById(UUID id);

    /**
     * The same lookup, holding a row lock for the rest of the transaction.
     *
     * <p>The household row is the serialisation point for changes to <em>membership</em>. Two
     * concurrent {@code DELETE /v1/admin/users/{id}} calls against the household's last two
     * administrators each counted two admins, each walked past the "this is the last one" guard,
     * and each committed — leaving a household nobody can administer and no API route back in.
     * Taking this lock first turns that guard from an observation into a decision. See
     * {@code UserAdminService}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Household h where h.id = :id")
    Optional<Household> findByIdForUpdate(@Param("id") UUID id);

    Household save(Household household);

    /**
     * Every household, oldest first.
     *
     * <p>Under the owner's one-shared-household-per-install decision this returns exactly one row,
     * and two callers need it: open self-serve registration, which has no invite to take a
     * household from, and {@code FirstRunBootstrap}, which needs to know whether it has already
     * run. Written as an ordered list rather than "the only one" so that the day the model grows
     * a second household this is a compile-time question rather than a silent wrong answer.
     */
    List<Household> findAllByOrderByCreatedAtAsc();

    long count();
}
