package finance.trove.categorisation;

import finance.trove.common.exception.ResourceNotFoundException;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The self-learning write-back seam — {@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D4's
 * {@code MerchantMappingWriter}, "per household and user, REQUIRES_NEW, idempotent upsert".
 *
 * <p><strong>This is the D4 self-invocation fix.</strong> The old service's
 * {@code persistMerchantMapping} was a package-private instance method on the same class as
 * {@code categorise}, called with a bare {@code this.persistMerchantMapping(...)} — a classic
 * Spring AOP self-invocation bypass, so its {@code @Transactional} was never applied through the
 * proxy and every write silently ran inside {@code categorise}'s own {@code readOnly = true}
 * transaction and failed, caught by a {@code catch (Exception)} that only logged a warning. A
 * write cannot bypass its own class's proxy when it lives in a different class entirely: this is
 * a distinct Spring bean, injected wherever it is called from, so any caller reaches it through
 * the real proxy and {@code REQUIRES_NEW} genuinely opens a second, independent transaction.
 *
 * <p>The AI categorisation tier ({@code DefaultAiCategorisationTier}) calls it through the
 * injected bean. {@code MerchantMappingIT#writeSurvivesOuterRollback} proves the write survives
 * an outer transaction's rollback.
 */
@Service
public class MerchantMappingWriter {

    private final MerchantCategoryMapRepository mappings;
    private final CategoryRepository categories;

    public MerchantMappingWriter(MerchantCategoryMapRepository mappings,
                                 CategoryRepository categories) {
        this.mappings = mappings;
        this.categories = categories;
    }

    /**
     * Records that {@code merchant} maps to {@code categoryId} for this household, in its own
     * transaction, independent of whatever the caller's own transaction does afterward.
     *
     * <p>Idempotent on {@code (householdId, normalisedMerchant)}: a second call for the same
     * merchant replaces the earlier row's category, confidence, match type and source rather than
     * creating a duplicate or failing on the unique constraint — required for a correction
     * (step 6: a member re-categorises a transaction whose merchant was already learned) and for
     * two concurrent writers racing the same merchant's first sighting.
     *
     * @param householdId who this mapping belongs to
     * @param userId      the member whose action produced this write, recorded as
     *                    {@code created_by}; may be {@code null} for an AI-sourced write with no
     *                    human actor
     * @param merchant    raw merchant string; normalised internally, so the caller need not
     *                    pre-normalise it
     * @param matchType   EXACT or PREFIX
     * @param categoryId  must resolve within this household (a system category or one of the
     *                    household's own) or {@link ResourceNotFoundException} is thrown, which
     *                    rolls back only this method's own transaction
     * @param confidence  in {@code [0.000, 1.000]}
     * @param source      how this write came about; never {@code RULE} from this method — a
     *                    {@code RULE} row is created directly by {@code CategoryRuleService}
     *                    through the rules API, not learned
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsert(UUID householdId, UUID userId, String merchant, MatchType matchType,
                       UUID categoryId, BigDecimal confidence, MappingSource source) {
        if (categories.findVisibleById(categoryId, householdId).isEmpty()) {
            throw ResourceNotFoundException.of("Category", categoryId);
        }
        String normalised = MerchantNormaliser.normalise(merchant);
        mappings.upsert(householdId, normalised, matchType.name(), categoryId, confidence,
            source.name(), userId);
    }
}
