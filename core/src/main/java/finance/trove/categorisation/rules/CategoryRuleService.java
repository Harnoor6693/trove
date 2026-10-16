package finance.trove.categorisation.rules;

import finance.trove.categorisation.CategorisationResult;
import finance.trove.categorisation.CategorisationService;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.categorisation.MappingSource;
import finance.trove.categorisation.MerchantCategoryMap;
import finance.trove.categorisation.MerchantCategoryMapRepository;
import finance.trove.categorisation.MerchantNormaliser;
import finance.trove.categorisation.rules.dto.CategoryRuleRequest;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ForbiddenException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.time.Instants;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The rules controller's business logic — list/create/update/delete/reorder over
 * {@code merchant_category_map} rows the caller's household created explicitly through the rules
 * UI ({@code source = RULE}), plus the dry-run that delegates straight to
 * {@link CategorisationService}.
 *
 * <p>Deliberately separate from {@link finance.trove.categorisation.MerchantMappingWriter}: that
 * class is the automated, idempotent-upsert seam self-learning writes (USER, AI) go through
 * later; a member explicitly creating or editing a rule here is a distinct action with different
 * failure semantics — a second rule for a merchant that already has a non-AI mapping (a rule the
 * member made) is 409 {@code conflict} (edit the existing one instead). An existing AI-sourced
 * guess is replaced in place (B1-06).
 */
@Service
public class CategoryRuleService {

    private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.900");

    private final MerchantCategoryMapRepository mappings;
    private final CategoryRepository categories;
    private final CategorisationService categorisationService;

    public CategoryRuleService(MerchantCategoryMapRepository mappings,
                               CategoryRepository categories,
                               CategorisationService categorisationService) {
        this.mappings = mappings;
        this.categories = categories;
        this.categorisationService = categorisationService;
    }

    /** This household's own rules, in precedence order. */
    @Transactional(readOnly = true)
    public List<MerchantCategoryMap> list(UUID householdId) {
        return mappings.findAllByHouseholdIdAndSourceOrderByPriorityAscCreatedAtAsc(householdId,
            MappingSource.RULE);
    }

    /**
     * Creates a rule. 409 {@code conflict} if this household already has a non-AI mapping (a rule
     * the member made) for the merchant. An existing AI-sourced guess is replaced in place (B1-06).
     */
    @Transactional
    public MerchantCategoryMap create(UUID householdId, UUID userId, CategoryRuleRequest request) {
        String normalised = normalisedOrThrow(request.merchant());
        validateCategory(householdId, request.categoryId());
        Optional<MerchantCategoryMap> existing =
            mappings.findByHouseholdIdAndNormalisedMerchant(householdId, normalised);
        if (existing.isPresent() && existing.get().getSource() != MappingSource.AI) {
            throw new ConflictException(
                "A rule for this merchant already exists in this household; update it instead");
        }
        if (existing.isPresent()) {
            // A rule is the member's decision and replaces a machine guess in place (B1-06).
            MerchantCategoryMap guess = existing.get();
            guess.setMatchType(request.matchType());
            guess.setCategoryId(request.categoryId());
            guess.setConfidence(confidenceOrDefault(request.confidence()));
            guess.setSource(MappingSource.RULE);
            guess.setPriority(nextPriority(householdId));
            return mappings.save(guess);
        }
        MerchantCategoryMap rule = new MerchantCategoryMap(householdId, normalised,
            request.matchType(), request.categoryId(), confidenceOrDefault(request.confidence()),
            MappingSource.RULE, nextPriority(householdId), userId, Instants.now());
        return mappings.save(rule);
    }

    /** Updates a rule the caller's household owns. */
    @Transactional
    public MerchantCategoryMap update(UUID householdId, UUID id, CategoryRuleRequest request) {
        MerchantCategoryMap rule = ownRule(householdId, id);
        String normalised = normalisedOrThrow(request.merchant());
        validateCategory(householdId, request.categoryId());
        if (!normalised.equals(rule.getNormalisedMerchant())) {
            Optional<MerchantCategoryMap> collision =
                mappings.findByHouseholdIdAndNormalisedMerchant(householdId, normalised);
            if (collision.isPresent() && !collision.get().getId().equals(id)) {
                throw new ConflictException(
                    "Another rule already exists for this merchant in this household");
            }
        }
        rule.setNormalisedMerchant(normalised);
        rule.setMatchType(request.matchType());
        rule.setCategoryId(request.categoryId());
        rule.setConfidence(confidenceOrDefault(request.confidence()));
        return mappings.save(rule);
    }

    /** Deletes a rule the caller's household owns. */
    @Transactional
    public void delete(UUID householdId, UUID id) {
        ownRule(householdId, id);
        mappings.deleteByIdAndHouseholdId(id, householdId);
    }

    /**
     * Sets {@code priority} to each id's index in {@code orderedIds}. Every id must already be
     * one of this household's rules or the whole call is refused before anything is written.
     */
    @Transactional
    public void reorder(UUID householdId, List<UUID> orderedIds) {
        List<MerchantCategoryMap> rules = new ArrayList<>(orderedIds.size());
        for (UUID id : orderedIds) {
            rules.add(ownRule(householdId, id));
        }
        for (int i = 0; i < rules.size(); i++) {
            rules.get(i).setPriority(i);
        }
        mappings.saveAll(rules);
    }

    /**
     * "Test this rule against merchant X" — runs the same rules-then-defaults pipeline as ingest,
     * without the AI tier that {@code CategorisationListener} adds after commit. Nothing is
     * persisted.
     */
    @Transactional(readOnly = true)
    public CategorisationResult dryRun(UUID householdId, String merchant, String description) {
        return categorisationService.categorise(householdId, merchant, description, null, null);
    }

    private MerchantCategoryMap ownRule(UUID householdId, UUID id) {
        MerchantCategoryMap rule = mappings.findByIdAndHouseholdId(id, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Rule", id));
        if (rule.getSource() != MappingSource.RULE) {
            throw new ForbiddenException(
                "Only rules created through this API can be edited or deleted here");
        }
        return rule;
    }

    private void validateCategory(UUID householdId, UUID categoryId) {
        categories.findVisibleById(categoryId, householdId)
            .orElseThrow(() -> new ValidationException(
                "categoryId does not resolve to a category visible to this household"));
    }

    private int nextPriority(UUID householdId) {
        List<MerchantCategoryMap> existing = list(householdId);
        return existing.isEmpty() ? 0 : existing.get(existing.size() - 1).getPriority() + 1;
    }

    private static String normalisedOrThrow(String merchant) {
        String normalised = MerchantNormaliser.normalise(merchant);
        if (normalised.isEmpty()) {
            throw new ValidationException(
                "merchant must contain at least one letter or digit after normalisation");
        }
        return normalised;
    }

    private static BigDecimal confidenceOrDefault(BigDecimal confidence) {
        return confidence == null ? DEFAULT_CONFIDENCE : confidence;
    }
}
