package finance.trove.taxexport.dto;

import finance.trove.categorisation.Category;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.taxexport.TaxLine;
import java.util.UUID;

/**
 * One category and the tax line the caller's export files it under, as
 * {@code GET /v1/tax-export/lines} and {@code PUT /v1/tax-export/lines/{categoryId}} return it.
 *
 * <p>{@code lineCode} and {@code lineLabel} are empty and {@code deductible} is false when no row
 * maps this category in this jurisdiction. The category still appears, and its transactions still
 * appear in the export with empty tax-line cells — a category with no label must never make its
 * transactions disappear from a member's filing.
 *
 * @param categorySlug the stable dotted id (e.g. {@code business.software}); the name is display
 *                     text and is not what anything joins on
 * @param overridden   {@code true} when the row is the caller's own, {@code false} for the seed
 *                     or for no row at all
 */
public record TaxLineMappingResponse(UUID categoryId, String categorySlug, String categoryName,
                                     Jurisdiction jurisdiction, String lineCode, String lineLabel,
                                     boolean deductible, boolean overridden) {

    /** Pairs a visible category with the line {@code TaxLineMappingService.resolve} gave it. */
    public static TaxLineMappingResponse of(Category category, Jurisdiction jurisdiction,
                                            TaxLine line) {
        return new TaxLineMappingResponse(category.getId(), category.getSlug(), category.getName(),
            jurisdiction, line.code(), line.label(), line.deductible(), line.overridden());
    }
}
