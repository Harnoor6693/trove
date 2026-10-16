package finance.trove.taxexport;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One transaction as the export writes it — the input both {@link CsvExportBuilder} and
 * {@link PdfExportBuilder} take, and the shape collapse step 18's full data export reuses
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9, §6; gap P2-16).
 *
 * <p>The first eleven components are the tax export's column list: {@code date, merchant,
 * description, notes, amount, currency, category, taxLineCode, taxLineLabel, receiptId, type}.
 * {@code accountId}, {@code transferGroupId} and {@code pending} are extra and written only by the
 * full data export ({@code CsvExportBuilder#buildLedger}). Two of the columns carry a decision:
 *
 * <ul>
 *   <li><strong>{@code notes} is its own column</strong> and never folded into
 *       {@code description} (RS-6). The source exported the member's PRIVATE notes under a
 *       heading that read {@code description}, indistinguishable from a merchant's own text in a
 *       file the member hands to an accountant.</li>
 *   <li><strong>{@code receiptId}</strong> is here because §2.9 fixes the column and the file's
 *       purpose is to let a member produce evidence for a line. Collapse step 14 added
 *       {@code transaction.receipt_id} (V38) and populates it; it is the receipt's <em>id</em> and
 *       nothing more — never a download URL, never a storage key, and above all never a figure
 *       computed from the receipt. The receipt's own GST/PST/HST split stays on the receipt and is
 *       never part of the tax export (§2.9, open question 1). The cell is empty for a transaction with no
 *       receipt attached, which is most of them.</li>
 * </ul>
 *
 * <p>{@code amount} is a {@link BigDecimal} at the ledger's {@code NUMERIC(19,4)} scale and
 * {@code currency} travels with it on every single row: a household holding two currencies does
 * NOT get one blended total (findings F1/F2), and there is no exchange rate anywhere in this
 * step.
 *
 * @param type the transaction's direction. {@code amount} is always positive, so without this a
 *     paycheque and a purchase look the same (B2-01)
 * @param accountId the account, transferGroupId the pairing id and pending the pending flag: written
 *     only by the full data export ({@link CsvExportBuilder#buildLedger}), never by the tax export
 * @param receiptId the attached receipt's id, or null — no URL, no key, no figure
 */
public record ExportRow(LocalDate date, String merchant, String description, String notes,
                        BigDecimal amount, CurrencyCode currency, String category,
                        String taxLineCode, String taxLineLabel, UUID receiptId,
                        TransactionType type, UUID accountId, UUID transferGroupId,
                        boolean pending) {
}
