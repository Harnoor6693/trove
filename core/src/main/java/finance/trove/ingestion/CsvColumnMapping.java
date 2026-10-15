package finance.trove.ingestion;

/**
 * Which column of an uploaded CSV file holds which field
 * ({@code docs/recovery/inventory/plugins.md} §8.2; {@code docs/recovery/07-READINESS-GAPS.md}
 * P2-12).
 *
 * <p>It is the CSV half of {@link ImportMapping} and therefore part of the shape stored in
 * {@code import_batch.mapping_json}. A closed record rather than a {@code Map<String,String>}
 * deliberately: the mapping is read back by the commit that runs days after the preview wrote it,
 * and a free map has no way to say that a key was misspelled.
 *
 * <p><strong>Either {@link #amountColumn()} alone or {@link #debitColumn()} and
 * {@link #creditColumn()} together</strong> — the single signed column is the common case, the
 * two-column layout is what several Canadian banks export. A row with both a debit and a credit
 * cell populated is refused rather than netted: it means the sniffer read the file wrongly, and
 * guessing would silently halve a statement.
 *
 * <p>{@link #balanceColumn()} is recognised so that a running-balance column is never mistaken for
 * an amount. Its values are never persisted.
 *
 * @param dateColumn        the header of the transaction-date column
 * @param descriptionColumn the header of the description/memo/payee column
 * @param amountColumn      the header of a single signed amount column, or {@code null}
 * @param debitColumn       the header of the debit/withdrawal column, or {@code null}
 * @param creditColumn      the header of the credit/deposit column, or {@code null}
 * @param currencyColumn    optional per-row currency; {@code null} means the account's own
 * @param balanceColumn     recognised and deliberately unused
 * @param dateFormat        a {@code java.time} pattern, or {@code null} while the file's dates are
 *                          ambiguous and the member has not chosen between the candidates
 * @param delimiter         the field separator the sniffer settled on
 * @param charset           how the bytes were decoded
 * @param hasHeader         whether the first line names the columns rather than being data
 */
public record CsvColumnMapping(String dateColumn, String descriptionColumn, String amountColumn,
                               String debitColumn, String creditColumn, String currencyColumn,
                               String balanceColumn, String dateFormat, String delimiter,
                               String charset, boolean hasHeader) {

    /** This mapping with {@code dateFormat} replaced — what a commit's adjustment produces. */
    public CsvColumnMapping withDateFormat(String replacement) {
        return new CsvColumnMapping(dateColumn, descriptionColumn, amountColumn, debitColumn,
            creditColumn, currencyColumn, balanceColumn, replacement, delimiter, charset,
            hasHeader);
    }

    /**
     * Whether the amount arrives as a debit/credit pair rather than one signed column.
     *
     * <p>Named {@code has…} rather than {@code is…} on purpose: this record is serialised straight
     * into {@code ImportPreviewResponse}, whose schema is closed, and Jackson auto-detects an
     * {@code isXxx()} method on a record as an extra property — which would put a
     * {@code debitCreditPair} field on the wire that the contract forbids.
     */
    public boolean hasDebitCreditPair() {
        return amountColumn == null && (debitColumn != null || creditColumn != null);
    }
}
