package finance.trove.taxexport;

import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.repository.UserOwnedRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link TaxLineMapping} — the interesting one, because the table holds two tiers.
 *
 * <p>A row with {@code user_id IS NULL} is the install-wide seed; a row with a member's id is that
 * member's override. The effective map is therefore <strong>one query</strong> with the override
 * winning, not two round trips and a merge in Java: two reads would have to agree about ordering
 * and about what happens when a member overrides only some categories, and the merge is where
 * that agreement rots.
 *
 * <p><strong>Why {@code DISTINCT ON}.</strong> Postgres 16 is pinned (§2.13), and
 * {@code DISTINCT ON (category_id) … ORDER BY category_id, user_id NULLS LAST} is a single
 * index-ordered pass over the rows the {@code (jurisdiction, category_id, user_id)} unique index
 * already keeps in that order. The two alternatives are worse here: a {@code LATERAL} runs a
 * correlated subquery per category, and a self-join with a {@code NOT EXISTS} anti-join states
 * the same rule twice — once as "prefer the override" and once as "and not the seed then" — which
 * is two places to get it wrong. {@code NULLS LAST} is the whole precedence rule and it is
 * expressed once.
 *
 * <p>{@link UserOwnedRepository} is the base every repository must extend since commit 571aa17.
 * Each query below takes the caller's id explicitly; none of them can see another member's
 * override.
 */
public interface TaxLineMappingRepository extends UserOwnedRepository<TaxLineMapping, UUID> {

    /**
     * The caller's effective map for one jurisdiction: their own row per category where they have
     * written one, the seed row otherwise, and nothing at all for a category neither tier maps.
     *
     * <p>The predicate {@code (user_id = :userId OR user_id IS NULL)} is what confines the read to
     * the two tiers that may apply — a household mate's override matches neither branch.
     */
    default List<TaxLineMapping> findEffectiveMap(Jurisdiction jurisdiction, UUID userId) {
        return findEffectiveMapIn(jurisdiction.name(), userId);
    }

    /**
     * The native half of {@link #findEffectiveMap}.
     *
     * <p>The jurisdiction arrives as its {@code name()} rather than as the enum itself because
     * this is a NATIVE query: Hibernate binds a Java enum parameter to a native statement by its
     * ordinal unless it is told otherwise, and the column stores {@code 'CA'}/{@code 'US'} — the
     * mismatch would silently match no row and hand every member an empty map. The default method
     * above is the only caller and does the conversion in one place.
     */
    @Query(value = "SELECT DISTINCT ON (m.category_id) m.* FROM tax_line_mapping m "
        + "WHERE m.jurisdiction = :jurisdiction "
        + "AND (m.user_id = :userId OR m.user_id IS NULL) "
        + "ORDER BY m.category_id, m.user_id NULLS LAST", nativeQuery = true)
    List<TaxLineMapping> findEffectiveMapIn(@Param("jurisdiction") String jurisdiction,
                                            @Param("userId") UUID userId);

    /**
     * Creates or replaces the caller's OWN row for one category and jurisdiction, in ONE
     * statement — the write behind {@code PUT /v1/tax-export/lines/{categoryId}}.
     *
     * <p><strong>Why a native upsert and not a JPA find-then-save.</strong> Read-then-write is a
     * check-then-act: two concurrent PUTs from the same member for the same category both read
     * "no row yet" in their own transaction, both insert, and the loser dies on the unique index
     * — which {@code GlobalExceptionHandler.handleDataIntegrity} turns into a 409 the PUT
     * operation does not declare. {@code INSERT … ON CONFLICT DO UPDATE} is atomic at the
     * database, so the second writer updates instead of colliding and the route has no 409 to
     * declare. This is exactly what {@code UNIQUE (jurisdiction, category_id, user_id) NULLS NOT
     * DISTINCT} was declared for (§2.9, §3, §4.12 item 2), and it is the same shape
     * {@code MerchantCategoryMapRepository.upsert} already uses in this tree.
     *
     * <p>The conflict target is named as the CONSTRAINT rather than inferred from a column list,
     * because the constraint is the thing the plan points at and naming it cannot silently pick a
     * different index later.
     *
     * <p>{@code user_id} is always the caller's here, never null: the {@code NULL}-keyed seed row
     * belongs to V36 and is never written through the API, so an override can never overwrite it.
     * {@code id}, {@code created_at} and {@code updated_at} take their column defaults on insert
     * and only {@code updated_at} is touched on update.
     */
    default void upsertOverride(Jurisdiction jurisdiction, UUID categoryId, UUID userId,
                                String lineCode, String lineLabel, boolean deductible) {
        upsertOverrideIn(jurisdiction.name(), categoryId, userId, lineCode, lineLabel, deductible);
    }

    /**
     * The native half of {@link #upsertOverride}.
     *
     * <p>The jurisdiction is bound as its {@code name()} for the same reason as in
     * {@link #findEffectiveMapIn}: Hibernate binds a Java enum to a native statement by its
     * ordinal, and the column stores {@code 'CA'}/{@code 'US'}.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO tax_line_mapping "
        + "(jurisdiction, category_id, user_id, line_code, line_label, deductible) "
        + "VALUES (:jurisdiction, :categoryId, :userId, :lineCode, :lineLabel, :deductible) "
        + "ON CONFLICT ON CONSTRAINT tax_line_mapping_scope_unique DO UPDATE SET "
        + "line_code = EXCLUDED.line_code, line_label = EXCLUDED.line_label, "
        + "deductible = EXCLUDED.deductible, updated_at = now()", nativeQuery = true)
    void upsertOverrideIn(@Param("jurisdiction") String jurisdiction,
                          @Param("categoryId") UUID categoryId, @Param("userId") UUID userId,
                          @Param("lineCode") String lineCode, @Param("lineLabel") String lineLabel,
                          @Param("deductible") boolean deductible);

    /** Every seed row for one jurisdiction — the seed-integrity assertions read through this. */
    @Query("select m from TaxLineMapping m where m.jurisdiction = :jurisdiction "
        + "and m.userId is null")
    List<TaxLineMapping> findSeedRows(@Param("jurisdiction") Jurisdiction jurisdiction);
}
