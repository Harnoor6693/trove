package finance.trove.taxexport.dto;

import finance.trove.taxexport.TaxExportFormat;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code POST /v1/tax-export/runs}.
 *
 * <p><strong>There is no {@code jurisdiction} field, on purpose.</strong> It comes from the
 * caller's own {@code users.country} — which owner decision 3 makes CA or US and nothing else —
 * and is stamped onto the run row, so a completed run stays reproducible from the row alone after
 * the member changes country. Letting a caller override it would mean a run whose meaning depends
 * on a request nobody kept.
 *
 * <p>The {@code @Max} here is a sanity bound, not the real ceiling: the service additionally
 * refuses any year later than the current one <em>in the caller's own timezone</em>
 * ({@code UserClock.today}), which Bean Validation cannot express because it is not a constant.
 */
public record CreateTaxExportRunRequest(
    @NotNull @Min(2000) @Max(2100) Integer taxYear,
    @NotNull TaxExportFormat format) {
}
