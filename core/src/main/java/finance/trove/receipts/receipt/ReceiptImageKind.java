package finance.trove.receipts.receipt;

/**
 * What one stored file behind a receipt is ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V37-row).
 *
 * <p>The pre-collapse schema had two columns on {@code receipt}, {@code image_key} and
 * {@code thumbnail_key}, which is why it could carry exactly one of each and could record neither
 * a size nor a digest for either. A row per file replaces them.
 */
public enum ReceiptImageKind {

    /** The re-encoded full-size image — never the bytes the client sent; see the upload service. */
    ORIGINAL,

    /** The small JPEG a list view renders. */
    THUMBNAIL,

    /**
     * One page of a multi-page document. Nothing writes this in the current version — PDF upload
     * is not accepted — and it exists because the unique constraint is over
     * {@code (receipt_id, kind, page_no)} and a page-bearing kind is what {@code page_no} is for.
     */
    PAGE
}
