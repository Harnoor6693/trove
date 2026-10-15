package finance.trove.ingestion.simplefin.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The body of {@code GET {accessUrl}/accounts}.
 *
 * @param accounts the accounts the access URL reaches; may be null or empty
 * @param errors   the bridge's own soft errors, e.g. an institution that needs re-authentication.
 *                 They are surfaced to the member through the sync log's error message and are
 *                 never logged verbatim: a bridge error string can quote an account name
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimplefinAccountSet(List<SimplefinAccount> accounts, List<String> errors) {

    /** Never-null accounts, so callers do not each repeat the same null check. */
    public List<SimplefinAccount> accountsOrEmpty() {
        return accounts == null ? List.of() : accounts;
    }
}
