package finance.trove.taxexport.dto;

import finance.trove.common.domain.Jurisdiction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A member's own mapping for one category, the body of
 * {@code PUT /v1/tax-export/lines/{categoryId}}.
 *
 * <p>{@code jurisdiction} is in the body rather than the path because it is part of the unique key
 * the write upserts on — {@code (jurisdiction, categoryId, userId)} — and a member legitimately
 * keeps a different line for the same category in each country.
 *
 * <p>There is deliberately no {@code userId} field: the owner is the authenticated caller and
 * nothing else. {@code SourceConventionTest} bans a client-supplied identity outright.
 */
public record TaxLineMappingRequest(
    @NotNull Jurisdiction jurisdiction,
    @NotBlank @Size(max = 20) String lineCode,
    @NotBlank @Size(max = 200) String lineLabel,
    @NotNull Boolean deductible) {
}
