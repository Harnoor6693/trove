package finance.trove.categorisation.dto;

import finance.trove.categorisation.Category;
import java.time.Instant;
import java.util.UUID;

/**
 * A category as every route in this package returns it — one shape for GET, POST and PUT, unlike
 * the old service, which serialised the raw JPA entity (nested {@code parent}, a field literally
 * named {@code system}) on GET while only POST used a DTO
 * (docs/recovery/06-COLLAPSE-PLAN.md §4.5, §2.3).
 *
 * @param householdId {@code null} for a system category
 * @param isSystem    {@code true} exactly when {@code householdId} is {@code null}; carried
 *                    explicitly so a client does not have to re-derive it, but it is never a
 *                    second source of truth — see {@link Category#isSystem()}
 */
public record CategoryResponse(UUID id, UUID householdId, String slug, String name, String icon,
                               UUID parentId, boolean isSystem, Instant createdAt,
                               Instant updatedAt) {

    public static CategoryResponse of(Category category) {
        return new CategoryResponse(category.getId(), category.getHouseholdId(),
            category.getSlug(), category.getName(), category.getIcon(), category.getParentId(),
            category.isSystem(), category.getCreatedAt(), category.getUpdatedAt());
    }
}
