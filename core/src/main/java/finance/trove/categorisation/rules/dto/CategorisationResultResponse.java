package finance.trove.categorisation.rules.dto;

import finance.trove.categorisation.CategorisationResult;
import finance.trove.categorisation.MappingSource;
import java.math.BigDecimal;
import java.util.UUID;

/** The dry-run response — a direct projection of {@link CategorisationResult}. */
public record CategorisationResultResponse(UUID categoryId, String categoryName,
                                           BigDecimal confidence, MappingSource source) {

    public static CategorisationResultResponse of(CategorisationResult result) {
        return new CategorisationResultResponse(result.categoryId(), result.categoryName(),
            result.confidence(), result.source());
    }
}
