package finance.trove.common.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * The base every repository over a <strong>household-owned</strong> table extends
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.5/§5, §9 risk 2).
 *
 * <p>The owner's tenancy decision is one shared household ledger per install: {@code household_id}
 * — not {@code user_id} — is the scoping column on {@code connected_account}, {@code transaction},
 * {@code budget_period}, {@code envelope}, {@code savings_goal}, {@code net_worth_snapshot},
 * {@code asset}, {@code liability}, {@code receipt}, {@code detected_subscription},
 * {@code subscription_alert} and {@code tax_export_run}. {@code user_id} on those tables means
 * "created by", for audit.
 *
 * <p><strong>It extends {@link Repository}, not {@code CrudRepository}, and that is the whole
 * point.</strong> {@code CrudRepository} would inherit {@code findById}, {@code findAll},
 * {@code deleteById} and {@code existsById} — four methods that take no household and therefore
 * hand any authenticated caller any other household's rows the moment a controller passes a path
 * variable straight through. They are not overridden here to throw; they are simply never
 * inherited, so the IDOR is a compile error rather than a code-review question.
 * {@code ArchitectureTest} additionally fails the build if a subinterface declares one by hand.
 *
 * <p>Repositories that need a query this base does not have declare it themselves — with
 * {@code householdId} as a parameter, which the ArchUnit rule also checks for.
 *
 * @param <T>  the entity type
 * @param <I>  the entity's identifier type, a {@link UUID} for every table in the P1 schema
 */
@NoRepositoryBean
public interface UserScopedRepository<T, I> extends Repository<T, I> {

    /** The row with this id <em>within this household</em>, or empty — never another's row. */
    Optional<T> findByIdAndHouseholdId(I id, UUID householdId);

    /** Every row of this household. For tables that are bounded by construction. */
    List<T> findAllByHouseholdId(UUID householdId);

    /** One page of this household's rows. */
    Page<T> findAllByHouseholdId(UUID householdId, Pageable pageable);

    boolean existsByIdAndHouseholdId(I id, UUID householdId);

    long countByHouseholdId(UUID householdId);

    /**
     * Deletes the row if it belongs to this household.
     *
     * @return the number of rows deleted: {@code 0} when the id belongs to another household,
     *         which the caller turns into a 404 (never a 403, §9 risk 2)
     */
    long deleteByIdAndHouseholdId(I id, UUID householdId);

    <S extends T> S save(S entity);

    <S extends T> List<S> saveAll(Iterable<S> entities);
}
