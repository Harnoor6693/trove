package finance.trove.categorisation;

import finance.trove.common.repository.UserScopedRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository over {@link MerchantCategoryMap}.
 *
 * <p>{@link #findPrefixMatches} is the D4 fix
 * (docs/recovery/01-FINDINGS.md D4, docs/recovery/inventory/categorisation-service.md §10): the
 * old service's derived query {@code findByMerchantStartingWith(prefix, ...)} generated
 * {@code WHERE merchant LIKE CONCAT(?1,'%')} — the STORED value had to start with the SUPPLIED
 * argument — while it was called with the full cleaned merchant name as that argument and
 * stored PREFIX rows are meant to hold short prefixes, so a stored {@code "APPLE"} row never
 * matched a lookup for {@code "APPLE STORE"}: the relationship was backwards. This query is
 * written the right way round — the SUPPLIED merchant must start with the STORED value — using a
 * length-bounded {@code substring} comparison rather than a LIKE pattern built from the stored
 * value, so a merchant string that happens to contain a SQL LIKE metacharacter ({@code %} or
 * {@code _}) cannot change which rows match (both are stripped by
 * {@link MerchantNormaliser#normalise(String)} in practice, but the query does not depend on
 * that for correctness).
 */
public interface MerchantCategoryMapRepository extends UserScopedRepository<MerchantCategoryMap, UUID> {

    /** The one row for this exact normalised merchant in this household, if any. */
    Optional<MerchantCategoryMap> findByHouseholdIdAndNormalisedMerchant(UUID householdId,
        String normalisedMerchant);

    /** The EXACT-type row for this merchant, if any — first tier of {@code categorise()}. */
    Optional<MerchantCategoryMap> findByHouseholdIdAndNormalisedMerchantAndMatchType(
        UUID householdId, String normalisedMerchant, MatchType matchType);

    /**
     * Every PREFIX row of this household whose stored value is a leading substring of
     * {@code merchant}, longest (most specific) first, then by {@code priority} descending — the
     * column the rules controller's reorder endpoint writes, for ties between prefixes of the
     * same length.
     */
    @Query("select m from MerchantCategoryMap m where m.householdId = :householdId "
        + "and m.matchType = finance.trove.categorisation.MatchType.PREFIX "
        + "and length(m.normalisedMerchant) <= length(:merchant) "
        + "and substring(:merchant, 1, length(m.normalisedMerchant)) = m.normalisedMerchant "
        + "order by length(m.normalisedMerchant) desc, m.priority desc")
    List<MerchantCategoryMap> findPrefixMatches(@Param("householdId") UUID householdId,
        @Param("merchant") String merchant);

    /** The household's rules for the rules controller, in display/precedence order. */
    List<MerchantCategoryMap> findAllByHouseholdIdAndSourceOrderByPriorityAscCreatedAtAsc(
        UUID householdId, MappingSource source);

    /** Whether any mapping in this household still points at this category — the delete guard. */
    boolean existsByHouseholdIdAndCategoryId(UUID householdId, UUID categoryId);

    /** Whether a mapping other than a machine guess (AI) still points at the category (B1-06). */
    boolean existsByHouseholdIdAndCategoryIdAndSourceNot(UUID householdId, UUID categoryId,
                                                         MappingSource source);

    /** Drops the household's AI guesses that point at a category being deleted (B1-06). */
    void deleteByHouseholdIdAndCategoryIdAndSource(UUID householdId, UUID categoryId,
                                                   MappingSource source);

    /**
     * Idempotent upsert on {@code (household_id, normalised_merchant)} — the write
     * {@link MerchantMappingWriter} wraps in its own {@code REQUIRES_NEW} transaction.
     *
     * <p>A native {@code INSERT ... ON CONFLICT DO UPDATE} rather than a JPA find-then-save: two
     * concurrent writers classifying the same merchant for the first time (e.g. two accounts
     * syncing at once) must not race a check-then-insert into a unique-constraint violation: this
     * statement is atomic at the database. {@code matchType} and {@code source} are bound as
     * plain strings (their {@code name()}), matching the VARCHAR + CHECK columns V15 declares.
     */
    @Modifying
    @Query(value = "INSERT INTO merchant_category_map "
        + "(household_id, normalised_merchant, match_type, category_id, confidence, source, "
        + "priority, created_by) "
        + "VALUES (:householdId, :merchant, :matchType, :categoryId, :confidence, :source, "
        + "0, :createdBy) "
        + "ON CONFLICT (household_id, normalised_merchant) DO UPDATE SET "
        + "match_type = EXCLUDED.match_type, category_id = EXCLUDED.category_id, "
        + "confidence = EXCLUDED.confidence, source = EXCLUDED.source, "
        + "created_by = EXCLUDED.created_by, updated_at = now()",
        nativeQuery = true)
    void upsert(@Param("householdId") UUID householdId, @Param("merchant") String merchant,
        @Param("matchType") String matchType, @Param("categoryId") UUID categoryId,
        @Param("confidence") BigDecimal confidence, @Param("source") String source,
        @Param("createdBy") UUID createdBy);
}
