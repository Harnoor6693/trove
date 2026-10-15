package finance.trove.ingestion.simplefin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One account a SimpleFIN bridge exposes.
 *
 * <p>{@code currency} is read from the ACCOUNT and not from an individual transaction, which is
 * SimpleFIN's own model — and it is what the connected account is created with, so that a US
 * account is never silently created as a Canadian-dollar one (finding F4, §9 risk 5). The balance
 * is deliberately not modelled: a {@code connected_account} balance column is gap P2-40 and
 * belongs to collapse step 9.
 *
 * @param id           the bridge's own id for the account — the value stored as
 *                     {@code connected_account.external_account_id}, which is what makes a
 *                     re-exchange reuse the row rather than duplicate it
 * @param name         a display name
 * @param currency     ISO 4217; may be a code this install does not support
 * @param org          the institution the bridge names; may be null
 * @param transactions may be null when the bridge has no history for the window
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimplefinAccount(String id, String name, String currency, SimplefinOrganisation org,
                               List<SimplefinTransaction> transactions) {
}
