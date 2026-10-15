package finance.trove.ingestion.importbatch.dto;

import finance.trove.ingestion.CsvColumnMapping;

/**
 * An adjustment to the column mapping the preview detected, sent with the commit.
 *
 * <p><strong>This is why there is no fifth route.</strong> Plan §6 describes the flow as "user
 * adjusts, POST commit": the member corrects a column the sniffer guessed wrong, or chooses
 * between two candidate date formats, and sends the correction with the commit that uses it. A
 * separate {@code PUT /v1/imports/{id}/mapping} would add a route, a second stored state and a
 * window in which a batch's mapping and its preview disagree, for nothing.
 *
 * <p>Every field is optional and an omitted one keeps its detected value, which is what
 * {@link #applyTo} does. {@code delimiter} and {@code charset} are deliberately absent: those are
 * facts about the bytes, established when the file was read, and re-declaring them would only let
 * a caller describe a file that is not the one that was uploaded.
 *
 * <h2>The amount columns are mutually exclusive, and the request may switch between them</h2>
 * Confirmed review finding 7. A file's amount arrives EITHER as one signed column OR as a
 * debit/credit pair — {@code CsvHeaderSniffer} only ever detects one shape or the other, and
 * {@code CsvSource} prefers a resolved debit/credit pair over {@code amountColumn} whenever both
 * are set. Merging each field independently meant a member correcting a sniffer that had wrongly
 * read the file as a debit/credit pair could send {@code amountColumn} and be answered 200 while
 * the commit went on reading the stale pair: an override the API accepted and silently ignored,
 * ending in transactions read from the wrong columns with no error anywhere. So the correction is
 * made whole here — stating {@code amountColumn} clears the pair, and stating either half of the
 * pair clears {@code amountColumn} — which is exactly the invariant detection already holds. A
 * request that states both is read the way {@code CsvSource} would read it: the pair wins.
 */
public record CsvColumnMappingRequest(String dateColumn, String descriptionColumn,
                                      String amountColumn, String debitColumn, String creditColumn,
                                      String currencyColumn, String balanceColumn,
                                      String dateFormat, Boolean hasHeader) {

    /** {@code detected}, with every field this request states replaced. */
    public CsvColumnMapping applyTo(CsvColumnMapping detected) {
        boolean statesAmount = states(amountColumn);
        boolean statesPair = states(debitColumn) || states(creditColumn);
        // The pair wins when a request states both, because that is what CsvSource does at read
        // time — an override must never describe a mapping the reader would not use.
        String amount = statesPair ? null
            : statesAmount ? amountColumn : merge(amountColumn, detected.amountColumn());
        String debit = statesPair ? debitColumn
            : statesAmount ? null : merge(debitColumn, detected.debitColumn());
        String credit = statesPair ? creditColumn
            : statesAmount ? null : merge(creditColumn, detected.creditColumn());
        return new CsvColumnMapping(
            merge(dateColumn, detected.dateColumn()),
            merge(descriptionColumn, detected.descriptionColumn()),
            amount, debit, credit,
            merge(currencyColumn, detected.currencyColumn()),
            merge(balanceColumn, detected.balanceColumn()),
            merge(dateFormat, detected.dateFormat()),
            detected.delimiter(), detected.charset(),
            hasHeader != null ? hasHeader : detected.hasHeader());
    }

    /**
     * Whether the request names a column here at all.
     *
     * <p>Blank is not naming one. A client that clears a field in a form sends {@code ""} rather
     * than omitting the key, and treating that as "this column is called the empty string" would
     * map it to no column while still counting as a statement that clears the other shape.
     */
    private static boolean states(String value) {
        return value != null && !value.isBlank();
    }

    /** The request's value where it states one, otherwise what the preview detected. */
    private static String merge(String requested, String detected) {
        return requested != null ? requested : detected;
    }
}
