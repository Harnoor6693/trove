package finance.trove.budgets.envelope.dto;

import finance.trove.budgets.envelope.Envelope;
import finance.trove.budgets.envelope.EnvelopeHealth;
import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * An envelope as every {@code /v1/budgets/{id}/envelopes*} operation returns it.
 *
 * <p>Health travels with every envelope, not only with the single-row read as in the source's
 * {@code EnvelopeWithHealth}. It is worth carrying now that {@code spent} is a real figure: a list
 * of envelopes whose health the client would have to fetch one at a time is a list the client
 * cannot render.
 *
 * @param categoryName joined from {@code category} on read, never stored — see {@link Envelope}
 * @param spent        the ledger's own answer, rewritten and never incremented (§6 D1)
 */
public record EnvelopeResponse(UUID id, UUID householdId, UUID periodId, UUID categoryId,
                               String categoryName, BigDecimal allocated, BigDecimal spent,
                               BigDecimal rolloverAmount, CurrencyCode currency,
                               EnvelopeHealth health, Instant createdAt, Instant updatedAt) {

    public static EnvelopeResponse of(Envelope envelope, String categoryName,
                                      EnvelopeHealth health) {
        return new EnvelopeResponse(envelope.getId(), envelope.getHouseholdId(),
            envelope.getPeriodId(), envelope.getCategoryId(), categoryName,
            envelope.getAllocated(), envelope.getSpent(), envelope.getRolloverAmount(),
            envelope.getCurrency(), health, envelope.getCreatedAt(), envelope.getUpdatedAt());
    }
}
