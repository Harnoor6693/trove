package finance.trove.accounts;

import finance.trove.common.domain.CurrencyCode;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The few columns of a categorised ledger row that an envelope recompute needs, read by
 * {@link ConnectedAccountRepository#categorisedRowsAffectedByDisconnect} before the rows change
 * or go.
 *
 * @param transactionId the row
 * @param categoryId    its category
 * @param date          its posting date; the month the envelope is keyed on
 * @param currency      its currency; envelopes are per currency
 */
public record CategorisedRow(UUID transactionId, UUID categoryId, LocalDate date,
                             CurrencyCode currency) {
}
