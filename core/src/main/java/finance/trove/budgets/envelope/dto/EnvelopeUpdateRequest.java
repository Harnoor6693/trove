package finance.trove.budgets.envelope.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * {@code PUT /v1/budgets/{id}/envelopes/{envelopeId}} body.
 *
 * <p>{@code allocated} is the only writable field and it is required — a full replace, not the
 * source's partial patch. {@code categoryId} and {@code currency} are fixed at creation:
 * re-pointing an envelope at another category would silently reinterpret the {@code spent} figure
 * already aggregated under it. {@code spent} and {@code rolloverAmount} are never client-writable.
 */
public record EnvelopeUpdateRequest(@NotNull @DecimalMin(value = "0.00", inclusive = true)
                                    BigDecimal allocated) {
}
