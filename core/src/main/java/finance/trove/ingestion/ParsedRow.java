package finance.trove.ingestion;

import finance.trove.common.plugin.RawTransaction;

/**
 * One data row of an uploaded file, as the parser read it.
 *
 * <p><strong>{@link #row()} is never {@code null}, even for a row the parser could not read.</strong>
 * That is deliberate and it is what keeps the import on the sync pipeline's existing failure path
 * rather than inventing a second one: a row whose amount could not be parsed is emitted with a
 * {@code null} amount, and {@code DeduplicationService.validate} refuses it with "the source
 * reported no amount", counted by {@code IngestionBatchWriter} and summarised into
 * {@code sync_log.error_message} exactly as a malformed bank row is. Dropping the row in the
 * parser instead would make {@code transactions_fetched} a count of the rows that happened to
 * parse, and a member would never learn that four lines of their statement were silently ignored.
 *
 * @param ordinal the row's position among the file's data rows, counting from 1. It is part of the
 *                CSV deduplication key, so two identical-looking rows are two transactions
 * @param row     the normalised row; never {@code null}, possibly missing required fields
 * @param error   why the parser could not read this row, for the preview to show; {@code null}
 *                when the row parsed cleanly. Never quotes the cell's own content
 */
public record ParsedRow(int ordinal, RawTransaction row, String error) {

    public ParsedRow {
        if (row == null) {
            throw new IllegalArgumentException("row must not be null; see the class javadoc");
        }
    }

    /** A row that parsed cleanly. */
    public static ParsedRow of(int ordinal, RawTransaction row) {
        return new ParsedRow(ordinal, row, null);
    }

    /** Whether the parser had something to say about this row. */
    public boolean hasError() {
        return error != null;
    }
}
