package finance.trove.budgets.envelope.dto;

import finance.trove.common.domain.CurrencyCode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * {@code POST /v1/budgets/{id}/envelopes} body ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.6).
 *
 * <p>The period comes from the path and the household from the signed token, so neither appears
 * here. Neither does {@code categoryName}: the pre-collapse request carried one and stored it,
 * so renaming a category left every envelope showing the old label. The name is joined on read.
 *
 * <p><strong>{@code spent} and {@code rolloverAmount} are deliberately absent.</strong>
 * {@code spent} is rewritten from the ledger by {@code EnvelopeSpendService} and
 * {@code rolloverAmount} is written only by the rollover job; a client that could set either
 * could make a budget say anything at all.
 *
 * @param categoryId a category this household can use — a system row or one of its own; one it
 *                   cannot use is 400 {@code validation-error}
 * @param currency   {@code null} means the PERIOD's currency. There is no literal default
 *                   anywhere (finding F1)
 */
public record EnvelopeRequest(@NotNull UUID categoryId,
                              @NotNull @DecimalMin(value = "0.00", inclusive = true)
                              BigDecimal allocated,
                              CurrencyCode currency) {
}
