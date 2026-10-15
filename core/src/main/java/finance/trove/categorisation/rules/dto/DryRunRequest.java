package finance.trove.categorisation.rules.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /v1/categories/rules/dry-run} — "test this rule against merchant X". Runs the real
 * {@code CategorisationService.categorise} pipeline against the caller's own household rules, the
 * community defaults and the {@code Uncategorised} fallback, without writing anything, so a
 * member can see what a sample merchant string would resolve to before — or after — saving a
 * rule for it.
 */
public record DryRunRequest(
    @NotBlank @Size(max = 500) String merchant,
    @Size(max = 500) String description) {
}
