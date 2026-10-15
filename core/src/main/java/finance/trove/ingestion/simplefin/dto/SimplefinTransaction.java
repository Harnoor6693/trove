package finance.trove.ingestion.simplefin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One transaction inside a {@link SimplefinAccount}, as a SimpleFIN bridge reports it.
 *
 * <p>Amounts are signed: positive is money in, negative is money out. {@code posted} is when it
 * settled; a null {@code posted} means the transaction is still pending.
 *
 * <p>{@code id} is the bridge's own identifier for the transaction and is what goes into
 * {@code transaction.provider_transaction_id}. The pre-collapse normaliser put the literal
 * {@code "simplefin"} there instead (finding O7/M0).
 *
 * @param id          the bridge's per-transaction identifier
 * @param amount      signed, in the account's currency
 * @param description the text the institution supplied
 * @param transacted  when it happened, from the member's point of view
 * @param posted      when it settled; null while pending
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimplefinTransaction(String id, BigDecimal amount, String description,
                                   Instant transacted, Instant posted) {
}
