package finance.trove.categorisation.rules.dto;

import finance.trove.categorisation.MatchType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code POST /v1/categories/rules}, {@code PUT /v1/categories/rules/{id}}.
 *
 * @param merchant   the raw pattern; normalised server-side, so callers need not pre-normalise
 * @param matchType  {@code EXACT} or {@code PREFIX}
 * @param categoryId must resolve to a category visible to the caller's household (a system
 *                   category or one of the household's own)
 * @param confidence optional; defaults to {@code 0.900} when absent
 */
public record CategoryRuleRequest(
    @NotBlank @Size(max = 255) String merchant,
    @NotNull MatchType matchType,
    @NotNull UUID categoryId,
    @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence) {
}
