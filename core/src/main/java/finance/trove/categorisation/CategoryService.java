package finance.trove.categorisation;

import finance.trove.categorisation.dto.CategoryRequest;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ForbiddenException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.time.Instants;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category CRUD, scoped to the caller's household ({@code docs/recovery/06-COLLAPSE-PLAN.md}
 * §4.5).
 *
 * <p>System rows (seeded by V14, {@code householdId == null}) are read-only through every
 * mutating method here — {@link #update} and {@link #delete} both refuse one with
 * {@link ForbiddenException} before any write. Household rows are fully editable by
 * any member of the owning household.
 */
@Service
public class CategoryService {

    private static final Pattern NON_SLUG_CHAR = Pattern.compile("[^a-z0-9]+");
    /** {@code category.slug} is {@code VARCHAR(60)} (V14). */
    private static final int SLUG_MAX_LENGTH = 60;
    private static final Pattern EDGE_DASH = Pattern.compile("^-+|-+$");

    private final CategoryRepository categories;
    private final MerchantCategoryMapRepository mappings;

    public CategoryService(CategoryRepository categories, MerchantCategoryMapRepository mappings) {
        this.categories = categories;
        this.mappings = mappings;
    }

    /** Every category visible to this household: the system seed plus its own. */
    @Transactional(readOnly = true)
    public List<Category> list(UUID householdId) {
        return categories.findAllVisible(householdId);
    }

    /**
     * Creates a household category. {@code parentId}, if given, must resolve to a category
     * visible to the household (its own or a system category).
     */
    @Transactional
    public Category create(UUID householdId, UUID userId, CategoryRequest request) {
        String name = request.name().trim();
        UUID parentId = validatedParent(householdId, request.parentId());
        String slug = uniqueSlug(householdId, name);
        Category category = new Category(householdId, slug, name, request.icon(), parentId,
            userId, Instants.now());
        return categories.save(category);
    }

    /** Updates a household's own category. A system category is 403 {@code forbidden}. */
    @Transactional
    public Category update(UUID householdId, UUID id, CategoryRequest request) {
        Category category = ownHouseholdCategory(householdId, id);
        UUID parentId = validatedParent(householdId, request.parentId());
        if (parentId != null && createsCycle(householdId, id, parentId)) {
            throw new ValidationException("parentId would make this category its own ancestor");
        }
        category.setName(request.name().trim());
        category.setIcon(request.icon());
        category.setParentId(parentId);
        return categories.save(category);
    }

    /**
     * Deletes a household's own category. A system category is 403 {@code forbidden}; a category
     * referenced by a non-AI merchant mapping (a rule the member made; {@code merchant_category
     * _map.category_id}) is 409 {@code conflict} — "in use" per §4.5's scope. AI-sourced mappings
     * are deleted with it.
     */
    @Transactional
    public void delete(UUID householdId, UUID id) {
        ownHouseholdCategory(householdId, id);
        if (mappings.existsByHouseholdIdAndCategoryIdAndSourceNot(householdId, id,
            MappingSource.AI)) {
            throw new ConflictException(
                "This category is in use by one or more merchant rules and cannot be deleted");
        }
        // An AI guess is not the member's decision; it must not hold a category hostage (B1-06).
        mappings.deleteByHouseholdIdAndCategoryIdAndSource(householdId, id, MappingSource.AI);
        categories.deleteByIdAndHouseholdId(id, householdId);
    }

    /** A household's own row, never a system one — the shared entry point for update/delete. */
    private Category ownHouseholdCategory(UUID householdId, UUID id) {
        Category category = categories.findVisibleById(id, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Category", id));
        if (category.isSystem()) {
            throw new ForbiddenException("System categories cannot be renamed or deleted");
        }
        return category;
    }

    private UUID validatedParent(UUID householdId, UUID parentId) {
        if (parentId == null) {
            return null;
        }
        categories.findVisibleById(parentId, householdId)
            .orElseThrow(() -> new ValidationException(
                "parentId does not resolve to a category visible to this household"));
        return parentId;
    }

    /** Walks the parent chain from {@code candidateParentId}; true if it reaches {@code id}. */
    private boolean createsCycle(UUID householdId, UUID id, UUID candidateParentId) {
        UUID current = candidateParentId;
        while (current != null) {
            if (current.equals(id)) {
                return true;
            }
            current = categories.findVisibleById(current, householdId)
                .map(Category::getParentId)
                .orElse(null);
        }
        return false;
    }

    /**
     * {@code slugify(name)}, deduplicated within the household by appending {@code -2}, {@code -3},
     * … The result always fits {@code category.slug VARCHAR(60)}: the base is cut to leave room
     * for the suffix, then any dash the cut exposed is stripped.
     */
    private String uniqueSlug(UUID householdId, String name) {
        String base = slugify(name);
        String candidate = fit(base, "");
        int suffix = 2;
        while (categories.existsByHouseholdIdAndSlug(householdId, candidate)) {
            candidate = fit(base, "-" + suffix++);
        }
        return candidate;
    }

    private static String fit(String base, String suffix) {
        String cut = base.substring(0, Math.min(base.length(), SLUG_MAX_LENGTH - suffix.length()));
        return EDGE_DASH.matcher(cut).replaceAll("") + suffix;
    }

    private static String slugify(String name) {
        String value = Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT);
        value = NON_SLUG_CHAR.matcher(value).replaceAll("-");
        value = EDGE_DASH.matcher(value).replaceAll("");
        return value.isEmpty() ? "category" : value;
    }
}
