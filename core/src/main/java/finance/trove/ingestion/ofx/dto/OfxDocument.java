package finance.trove.ingestion.ofx.dto;

import java.util.List;

/**
 * A parsed OFX or QFX file: the account it describes and the transactions in it.
 *
 * @param account      never {@code null} — a file with no {@code ACCTID} is refused before this
 *                     record is built
 * @param transactions in file order, capped by {@code ImportLimits.maxRows}
 */
public record OfxDocument(OfxAccount account, List<OfxTransaction> transactions) {

    public OfxDocument {
        transactions = List.copyOf(transactions);
    }
}
