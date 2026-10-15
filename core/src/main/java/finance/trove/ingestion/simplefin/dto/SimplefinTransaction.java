package finance.trove.ingestion.simplefin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * One transaction inside a {@link SimplefinAccount}, as a SimpleFIN bridge reports it.
 *
 * <p>Amounts are signed: positive is money in, negative is money out. {@code posted} is when it
 * settled; a null or zero {@code posted}, or {@code "pending": true}, means it is still pending.
 *
 * <p>B1-05: the field names are the protocol's own. {@code transacted_at} never bound to a
 * component named {@code transacted}, so every row was dated by its posting day. The amount is
 * kept as the bridge's text and read per row by the normaliser, so one amount the ledger cannot
 * store refuses that row with a reason instead of failing the whole response.
 *
 * <p>{@code id} is the bridge's own identifier for the transaction and is what goes into
 * {@code transaction.provider_transaction_id}. The pre-collapse normaliser put the literal
 * {@code "simplefin"} there instead (finding O7/M0).
 *
 * @param id          the bridge's per-transaction identifier
 * @param amount      signed, in the account's currency, as the bridge wrote it
 * @param description the text the institution supplied
 * @param transacted  {@code transacted_at}: when it happened, from the member's point of view
 * @param posted      when it settled; null, or the epoch, while pending
 * @param pending     {@code true} while the bank has not settled it; optional
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimplefinTransaction(String id, String amount, String description,
                                   @JsonProperty("transacted_at") Instant transacted,
                                   Instant posted, Boolean pending) {
}
