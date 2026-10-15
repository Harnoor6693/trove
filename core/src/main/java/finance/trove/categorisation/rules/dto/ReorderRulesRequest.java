package finance.trove.categorisation.rules.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * {@code POST /v1/categories/rules/reorder}. {@code orderedIds} must name every rule the caller
 * wants reordered, in the precedence order they should take among PREFIX matches of equal
 * specificity — index 0 wins first. Every id must belong to the caller's household and to a
 * {@code RULE}-sourced row, or the whole request is refused (partial reordering is not offered).
 */
public record ReorderRulesRequest(@NotEmpty List<UUID> orderedIds) {
}
