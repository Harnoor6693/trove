package finance.trove.categorisation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The outcome of {@link CategorisationService#categorise}.
 *
 * @param categoryId   never {@code null} — the pipeline always resolves to a category, falling
 *                     back to the {@code Uncategorised} system row rather than answering "none"
 * @param categoryName the resolved category's display name, for a caller that wants to show a
 *                     result without a second lookup (the rules dry-run response, for one)
 * @param confidence   in {@code [0.000, 1.000]}; {@code 0.000} for the {@code Uncategorised}
 *                     fallback, otherwise the confidence carried by whichever tier matched
 * @param source       {@code null} when the match came from the community-registry tier or the
 *                     {@code Uncategorised} fallback — neither is a persisted household row, so
 *                     neither can honestly claim {@link MappingSource#RULE},
 *                     {@link MappingSource#USER} or {@link MappingSource#AI}
 */
public record CategorisationResult(UUID categoryId, String categoryName, BigDecimal confidence,
                                   MappingSource source) {
}
