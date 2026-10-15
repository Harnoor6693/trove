package finance.trove.categorisation;

import finance.trove.common.repository.UserScopedRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link Category}.
 *
 * <p>Extends {@link UserScopedRepository} per the tenancy rule every household-owned table
 * follows ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risk 2; {@code ArchitectureTest}), but
 * {@code category} is not <em>purely</em> household-owned — a system row's {@code household_id}
 * is null and must still be visible to every household. The inherited
 * {@code findByIdAndHouseholdId}/{@code findAllByHouseholdId} would silently exclude every
 * system row (an equality comparison against {@code NULL} is never true in SQL), so this
 * interface declares the two queries that actually reflect "visible to this household": id and
 * household both take {@code householdId} as ArchUnit requires, and both are written as their
 * own name rather than reusing the banned unscoped-finder names.
 */
public interface CategoryRepository extends UserScopedRepository<Category, UUID> {

    /** The row with this id if it is a system category, or belongs to this household — else empty. */
    @Query("select c from Category c where c.id = :id "
        + "and (c.householdId = :householdId or c.householdId is null)")
    Optional<Category> findVisibleById(@Param("id") UUID id, @Param("householdId") UUID householdId);

    /** Every system category plus this household's own, by name. */
    @Query("select c from Category c where c.householdId = :householdId or c.householdId is null "
        + "order by c.name asc")
    List<Category> findAllVisible(@Param("householdId") UUID householdId);

    /** For slug-collision checks on create; system rows share the {@code householdId IS NULL} slot. */
    boolean existsByHouseholdIdAndSlug(UUID householdId, String slug);

    /**
     * By slug within a scope — {@code null} for a system row. Used to resolve the
     * {@code Uncategorised} fallback by its stable slug rather than its (seed-order-dependent)
     * name.
     */
    Optional<Category> findByHouseholdIdAndSlug(UUID householdId, String slug);

    /**
     * By display name within a scope — {@code null} for a system row. The community merchant
     * registry's entries name a category by {@code categoryName}, not by slug, so this is what
     * resolves them.
     */
    Optional<Category> findByHouseholdIdAndName(UUID householdId, String name);
}
