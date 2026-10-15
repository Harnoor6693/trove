package finance.trove.categorisation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The categorisation pipeline: household rules, then the built-in community defaults, then a
 * guaranteed fallback — the service API {@code docs/recovery/06-COLLAPSE-PLAN.md} §6's D4 seam
 * describes and steps 6, 16 and 17 call.
 *
 * <p>Five tiers, each returning as soon as one matches:
 * <ol>
 *   <li>An EXACT {@link MerchantCategoryMap} row for this household.</li>
 *   <li>The best PREFIX {@link MerchantCategoryMap} row for this household — longest stored
 *       prefix first (see {@link MerchantCategoryMapRepository#findPrefixMatches}, the D4 prefix
 *       fix).</li>
 *   <li>{@link CommunityMerchantRegistry} — the built-in defaults, which only ever resolve to a
 *       SYSTEM category.</li>
 *   <li>{@link AiCategorisationTier} — the transaction owner's own AI, and <strong>only</strong>
 *       when a caller named that owner (collapse step 17). It sits here, after the free tiers and
 *       before the fallback, because a household's own rules are a member's explicit statement and
 *       the registry is free and instant, while the model is the thing that costs money, time and
 *       privacy.</li>
 *   <li>The {@code Uncategorised} system category, so this method never returns "no category":
 *       every caller downstream (a transaction row, a budget aggregate) can assume
 *       {@code categoryId} is always set.</li>
 * </ol>
 *
 * <p><strong>An install with no AI runs exactly the four tiers it ran before.</strong> The
 * five-argument {@link #categorise(UUID, String, String, BigDecimal, String)} names no owner and
 * therefore never consults tier 4 at all — not even to ask whether AI is on — so the rules
 * dry-run and every existing caller behave byte for byte as they did. Only the six-argument
 * overload, which a caller must deliberately pass a user id to, can reach a model.
 *
 * <p>{@code description}, {@code amount} and {@code currency} are accepted because the service
 * interface later steps call is fixed at {@code categorise(householdId, merchant, description,
 * amount, currency)} — step 5's matching logic is merchant-only, exactly what the old service
 * did; a value- or description-based rule tier is a later step's addition, not a change to this
 * method's shape.
 *
 * <p><strong>Nothing here writes.</strong> This is the D4 self-invocation fix applied by
 * construction rather than by patching the old call site: {@code categorise} stays
 * {@code readOnly = true} for real, because it has no write of its own to accidentally run
 * inside its own transaction. Writing a learned mapping back (the old service's self-learning
 * cache, and step 6/16/17's job) goes through {@link MerchantMappingWriter} — a different Spring
 * bean, called by a different caller, in its own {@code REQUIRES_NEW} transaction — never by this
 * class calling a sibling method on itself.
 */
@Service
public class CategorisationService {

    /** Slug of the mandatory pipeline fallback, stable across a rename (V14). */
    static final String UNCATEGORISED_SLUG = "system.uncategorised";

    private static final BigDecimal NO_CONFIDENCE = new BigDecimal("0.000");

    private final CategoryRepository categories;
    private final MerchantCategoryMapRepository mappings;
    private final CommunityMerchantRegistry communityRegistry;
    private final AiCategorisationTier aiTier;

    public CategorisationService(CategoryRepository categories,
                                 MerchantCategoryMapRepository mappings,
                                 CommunityMerchantRegistry communityRegistry,
                                 AiCategorisationTier aiTier) {
        this.categories = categories;
        this.mappings = mappings;
        this.communityRegistry = communityRegistry;
        this.aiTier = aiTier;
    }

    /**
     * Resolves the best category for one merchant, within one household.
     *
     * @param householdId who is asking — every rule tier is scoped to this household alone
     * @param merchant    the raw, bank-supplied merchant string; normalised internally
     * @param description reserved for a future description-based rule tier; unused today
     * @param amount      reserved for a future value-based rule tier; unused today
     * @param currency    reserved alongside {@code amount}; unused today
     * @return never {@code null}
     */
    @Transactional(readOnly = true)
    public CategorisationResult categorise(UUID householdId, String merchant, String description,
                                           BigDecimal amount, String currency) {
        return resolve(householdId, null, merchant);
    }

    /**
     * The same pipeline, plus the AI tier, for a caller that knows whose row this is.
     *
     * <p>The owner is named rather than inferred because {@code ai_settings} is keyed by user id
     * (V16, owner decision 9): the endpoint, the key, the model and the privacy tier that would be
     * used belong to one member, and there is no "the install's AI" to fall back on. On the ingest
     * path the owner is {@code TransactionsIngestedEvent.userId}; on a manual entry it is the
     * principal.
     *
     * <p>This method stays {@code readOnly = true} and still never returns {@code null}. The
     * learned write-back a confident answer produces happens inside {@link AiCategorisationTier},
     * through the injected {@link MerchantMappingWriter} and its own {@code REQUIRES_NEW}
     * transaction, so nothing this class holds is ever written from a read-only session.
     *
     * @param userId the transaction OWNER, or {@code null} for an ownerless row — a row with no
     *               owner takes the non-AI path and falls through to {@code Uncategorised},
     *               because there is no member whose endpoint and whose money an AI call would be
     * @return never {@code null}
     */
    @Transactional(readOnly = true)
    public CategorisationResult categorise(UUID householdId, UUID userId, String merchant,
                                           String description, BigDecimal amount,
                                           String currency) {
        return resolve(householdId, userId, merchant);
    }

    /**
     * The tiers themselves.
     *
     * <p>Private, and called directly by both public overloads rather than one overload calling
     * the other, so that no method on this class is ever reached through {@code this} in a way
     * that could look like — or later become — the self-invocation D4 was.
     */
    private CategorisationResult resolve(UUID householdId, UUID userId, String merchant) {
        String cleaned = MerchantNormaliser.normalise(merchant);

        Optional<MerchantCategoryMap> exact = mappings
            .findByHouseholdIdAndNormalisedMerchantAndMatchType(householdId, cleaned, MatchType.EXACT);
        if (exact.isPresent()) {
            return fromMapping(exact.get());
        }

        List<MerchantCategoryMap> prefixMatches = mappings.findPrefixMatches(householdId, cleaned);
        if (!prefixMatches.isEmpty()) {
            return fromMapping(prefixMatches.get(0));
        }

        Optional<CommunityMerchantRegistry.Entry> community = communityRegistry.lookup(cleaned);
        if (community.isPresent()) {
            Optional<Category> resolved =
                categories.findByHouseholdIdAndName(null, community.get().categoryName());
            if (resolved.isPresent()) {
                Category category = resolved.get();
                return new CategorisationResult(category.getId(), category.getName(),
                    community.get().confidence(), null);
            }
        }

        if (userId != null) {
            Optional<CategorisationResult> ai = aiTier.categorise(householdId, userId, merchant);
            if (ai.isPresent()) {
                return ai.get();
            }
        }

        return uncategorised();
    }

    private CategorisationResult uncategorised() {
        Category fallback = categories.findByHouseholdIdAndSlug(null, UNCATEGORISED_SLUG)
            .orElseThrow(() -> new IllegalStateException(
                "The Uncategorised system category is missing; V14 did not seed correctly"));
        return new CategorisationResult(fallback.getId(), fallback.getName(), NO_CONFIDENCE, null);
    }

    private CategorisationResult fromMapping(MerchantCategoryMap mapping) {
        Category category = categories
            .findVisibleById(mapping.getCategoryId(), mapping.getHouseholdId())
            .orElseThrow(() -> new IllegalStateException("MerchantCategoryMap " + mapping.getId()
                + " points at a category that no longer exists"));
        return new CategorisationResult(category.getId(), category.getName(),
            mapping.getConfidence(), mapping.getSource());
    }
}
