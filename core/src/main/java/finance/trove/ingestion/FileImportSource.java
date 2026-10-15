package finance.trove.ingestion;

import finance.trove.common.plugin.BankSource;

/**
 * A {@link BankSource} whose transactions come from a file a member uploaded rather than from a
 * network call ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.12, §6 "CSV/OFX/QFX import").
 *
 * <p><strong>{@link BankSource#fetch} ignores its date window for these sources</strong>, and that
 * is a decision rather than an oversight. A window exists so a network sync does not re-download a
 * bank's whole history on every run; an uploaded file has no history to re-download — it is
 * exactly the rows the member chose to export. Filtering it against the account's sync window
 * would silently drop the older half of a statement, with nothing anywhere to tell the member it
 * had happened. The file IS the window.
 *
 * <p>{@link #read} is the same parse the {@code fetch} does, exposed so that
 * {@code POST /v1/imports} can show a preview <strong>without writing a single transaction row</strong>.
 * It touches no database and no storage: the caller hands it bytes it has already read and
 * size-checked.
 */
public interface FileImportSource extends BankSource {

    /**
     * Parses {@code content} into rows, plus whatever the file says about its own account.
     *
     * @param content the uploaded bytes, already checked against {@code ImportLimits}
     * @param mapping the batch's mapping; its {@code csv} half may be {@code null} on a first read,
     *                in which case the source sniffs one and returns it
     * @param context what the caller already knows about the destination
     * @return the parsed file; never {@code null}
     * @throws finance.trove.common.plugin.BankSourceConnectionException if the bytes are not this
     *         format at all, or if the file breaks a limit
     */
    ParsedUpload read(byte[] content, ImportMapping mapping, ImportContext context);
}
