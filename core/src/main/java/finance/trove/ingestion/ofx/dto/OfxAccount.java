package finance.trove.ingestion.ofx.dto;

/**
 * The account an OFX document describes, from its {@code BANKACCTFROM} / {@code CCACCTFROM} block.
 *
 * <p><strong>{@link #accountId()} is never null and never a placeholder.</strong> The pre-collapse
 * SGML parser defaulted a missing {@code ACCTID} to the literal {@code "unknown"} while the XML
 * parser threw — the two disagreed, and the SGML side was the dangerous one: step 7 uses
 * {@code ACCTID} as {@code connected_account.external_account_id}, whose uniqueness key is
 * {@code (household_id, source_kind, external_account_id)}. A shared literal would therefore make
 * every ACCTID-less upload in a household resolve to ONE account, merging two different banks'
 * statements into a single ledger row set. Both parsers now refuse the file, and the member is
 * told which tag is missing.
 *
 * @param accountId   {@code ACCTID}, the institution's own id for the account
 * @param bankId      {@code BANKID}, the routing/institution number; empty when absent
 * @param accountType {@code ACCTTYPE}; {@code CHECKING} when absent, which is the OFX default
 * @param currency    {@code CURDEF}, the document's default currency, or {@code null} when the
 *                    file declares none — which means the ACCOUNT's, never a literal (finding F4)
 */
public record OfxAccount(String accountId, String bankId, String accountType, String currency) {
}
