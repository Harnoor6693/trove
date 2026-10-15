package finance.trove.categorisation.rules;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.categorisation.Category;
import finance.trove.categorisation.CategoryRepository;
import finance.trove.categorisation.MerchantCategoryMap;
import finance.trove.categorisation.rules.dto.CategorisationResultResponse;
import finance.trove.categorisation.rules.dto.CategoryRuleRequest;
import finance.trove.categorisation.rules.dto.CategoryRuleResponse;
import finance.trove.categorisation.rules.dto.DryRunRequest;
import finance.trove.categorisation.rules.dto.ReorderRulesRequest;
import finance.trove.common.dto.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Household merchant rules ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.5): explicit
 * merchant-to-category mappings a member creates through the UI, distinct from the mappings
 * {@code MerchantMappingWriter} learns automatically later.
 */
@RestController
@RequestMapping("/v1/categories/rules")
public class CategoryRuleController {

    private final CategoryRuleService ruleService;
    private final CategoryRepository categories;

    public CategoryRuleController(CategoryRuleService ruleService, CategoryRepository categories) {
        this.ruleService = ruleService;
        this.categories = categories;
    }

    /** {@code GET /v1/categories/rules} — this household's rules, in precedence order. */
    @GetMapping
    public ApiResponse<List<CategoryRuleResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal) {
        UUID householdId = principal.getHouseholdId();
        return ApiResponse.ok(ruleService.list(householdId).stream()
            .map(rule -> toResponse(rule, householdId))
            .toList());
    }

    /** {@code POST /v1/categories/rules}. */
    @PostMapping
    public ResponseEntity<ApiResponse<CategoryRuleResponse>> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody CategoryRuleRequest request) {
        UUID householdId = principal.getHouseholdId();
        MerchantCategoryMap created = ruleService.create(householdId, principal.getUserId(),
            request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(toResponse(created, householdId)));
    }

    /** {@code PUT /v1/categories/rules/{id}}. */
    @PutMapping("/{id}")
    public ApiResponse<CategoryRuleResponse> update(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @Valid @RequestBody CategoryRuleRequest request) {
        UUID householdId = principal.getHouseholdId();
        return ApiResponse.ok(
            toResponse(ruleService.update(householdId, id, request), householdId));
    }

    /** {@code DELETE /v1/categories/rules/{id}}. */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        ruleService.delete(principal.getHouseholdId(), id);
        return ApiResponse.<Void>ok(null);
    }

    /**
     * {@code POST /v1/categories/rules/reorder} — sets {@code priority} to each id's position in
     * {@code orderedIds}, breaking ties between PREFIX rules of equal specificity.
     */
    @PostMapping("/reorder")
    public ApiResponse<Void> reorder(@AuthenticationPrincipal TroveUserDetails principal,
                                     @Valid @RequestBody ReorderRulesRequest request) {
        ruleService.reorder(principal.getHouseholdId(), request.orderedIds());
        return ApiResponse.<Void>ok(null);
    }

    /**
     * {@code POST /v1/categories/rules/dry-run} — "test this rule against merchant X". Runs the
     * live categorisation pipeline and writes nothing.
     */
    @PostMapping("/dry-run")
    public ApiResponse<CategorisationResultResponse> dryRun(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody DryRunRequest request) {
        return ApiResponse.ok(CategorisationResultResponse.of(
            ruleService.dryRun(principal.getHouseholdId(), request.merchant(),
                request.description())));
    }

    private CategoryRuleResponse toResponse(MerchantCategoryMap rule, UUID householdId) {
        String categoryName = categories.findVisibleById(rule.getCategoryId(), householdId)
            .map(Category::getName)
            .orElse(null);
        return CategoryRuleResponse.of(rule, categoryName);
    }
}
