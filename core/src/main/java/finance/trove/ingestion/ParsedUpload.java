package finance.trove.ingestion;

import finance.trove.accounts.AccountType;
import finance.trove.common.domain.CurrencyCode;
import java.util.List;

/**
 * Everything reading one uploaded file produced, before anything is written
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-12).
 *
 * <p>It carries what the file says about the ACCOUNT as well as its rows, because an OFX file
 * names its own account in {@code ACCTID} and that is what makes re-uploading the same statement
 * reuse the account it created the first time instead of making a second one and duplicating every
 * transaction on it ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 M0, blocker).
 *
 * @param rows                 every data row, in file order; a row the parser could not read is
 *                             present with its reason rather than dropped (see {@link ParsedRow})
 * @param externalAccountId    the account identity the file declares — OFX's {@code ACCTID} —
 *                             or {@code null} for a format that declares none, which is every CSV
 * @param institution          the institution the file names ({@code BANKID}), or {@code null}
 * @param accountType          the product the file names ({@code ACCTTYPE}), or {@code null}
 * @param currency             the currency the file declares ({@code CURDEF}), or {@code null},
 *                             which means the account's own — never a literal (finding F4)
 * @param mapping              the column mapping used, for a CSV; {@code null} for OFX
 * @param dateFormatCandidates populated only when the file's dates are genuinely ambiguous
 * @param warnings             things the member should read before committing. Never file content
 */
public record ParsedUpload(List<ParsedRow> rows, String externalAccountId, String institution,
                           AccountType accountType, CurrencyCode currency,
                           CsvColumnMapping mapping,
                           List<DateFormatCandidate> dateFormatCandidates,
                           List<String> warnings) {

    public ParsedUpload {
        rows = List.copyOf(rows);
        dateFormatCandidates = List.copyOf(dateFormatCandidates);
        warnings = List.copyOf(warnings);
    }
}
