package finance.trove.ingestion.ofx.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One {@code <STMTTRN>} block, with the raw field values the OFX specification defines.
 *
 * <p>{@link #fitId()} is the institution's unique transaction id. It is the value that goes into
 * {@code transaction.provider_transaction_id} — the exact deduplication key — and the
 * pre-collapse normaliser put the literal {@code "ofx"} there instead, which with the unique index
 * on {@code (account_id, provider_transaction_id)} meant an OFX account could hold exactly one
 * transaction for ever (finding O7/M0).
 *
 * @param fitId      {@code FITID}, or {@code null} when the block has none
 * @param type       {@code TRNTYPE}
 * @param datePosted {@code DTPOSTED}
 * @param dateUser   {@code DTUSER}
 * @param amount     {@code TRNAMT}, signed as the institution wrote it, or {@code null} when it
 *                   is missing or not a number the ledger can store
 * @param name       {@code NAME}
 * @param memo       {@code MEMO}
 * @param checkNum   {@code CHECKNUM}
 * @param currency   {@code CURRENCY/CURSYM} on this transaction, or {@code null} for the
 *                   document's {@code CURDEF}
 */
public record OfxTransaction(String fitId, String type, LocalDate datePosted, LocalDate dateUser,
                             BigDecimal amount, String name, String memo, String checkNum,
                             String currency) {
}
