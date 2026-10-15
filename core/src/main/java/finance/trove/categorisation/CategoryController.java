package finance.trove.categorisation;

import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.categorisation.dto.CategoryRequest;
import finance.trove.categorisation.dto.CategoryResponse;
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
 * Category CRUD ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.5). Identity is always
 * {@link TroveUserDetails#getHouseholdId()} from the signed token — never a path variable, a
 * query parameter or a request body field.
 */
@RestController
@RequestMapping("/v1/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** {@code GET /v1/categories} — the system seed plus this household's own, by name. */
    @GetMapping
    public ApiResponse<List<CategoryResponse>> list(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(categoryService.list(principal.getHouseholdId()).stream()
            .map(CategoryResponse::of)
            .toList());
    }

    /** {@code POST /v1/categories} — creates a household category. */
    @PostMapping
    public ResponseEntity<ApiResponse<CategoryResponse>> create(
        @AuthenticationPrincipal TroveUserDetails principal,
        @Valid @RequestBody CategoryRequest request) {
        Category created = categoryService.create(principal.getHouseholdId(),
            principal.getUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.ok(CategoryResponse.of(created)));
    }

    /**
     * {@code PUT /v1/categories/{id}} — renames or re-parents a household category. A system
     * category is 403 {@code forbidden}.
     */
    @PutMapping("/{id}")
    public ApiResponse<CategoryResponse> update(
        @AuthenticationPrincipal TroveUserDetails principal, @PathVariable UUID id,
        @Valid @RequestBody CategoryRequest request) {
        return ApiResponse.ok(CategoryResponse.of(
            categoryService.update(principal.getHouseholdId(), id, request)));
    }

    /**
     * {@code DELETE /v1/categories/{id}}. A system category is 403 {@code forbidden}; a category
     * still referenced by one of this household's merchant rules is 409 {@code conflict}.
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal TroveUserDetails principal,
                                    @PathVariable UUID id) {
        categoryService.delete(principal.getHouseholdId(), id);
        return ApiResponse.<Void>ok(null);
    }
}
