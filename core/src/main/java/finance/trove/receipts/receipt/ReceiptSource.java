package finance.trove.receipts.receipt;

/**
 * How a receipt image arrived ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V37-row).
 *
 * <p>The source enum also carried {@code EMAIL}. There is no email ingress for receipts in this
 * design and none is planned, so the value is gone rather than kept as a state nothing can reach:
 * the column's CHECK constraint lists exactly these two.
 */
public enum ReceiptSource {

    /** A file the member chose. */
    UPLOAD,

    /** A photograph taken in the app. */
    SCAN;

    /**
     * Resolves the multipart {@code source} part, defaulting an absent or unrecognised value to
     * {@link #UPLOAD} rather than refusing the upload.
     *
     * <p>Lenient on purpose, and only here: the part is a hint about provenance that affects
     * nothing but a label, and refusing a receipt photograph because a client sent
     * {@code "camera"} would lose the member's data over a display string. Everything the upload
     * validates strictly is about the bytes.
     */
    public static ReceiptSource from(String raw) {
        if (raw == null) {
            return UPLOAD;
        }
        for (ReceiptSource candidate : values()) {
            if (candidate.name().equalsIgnoreCase(raw.strip())) {
                return candidate;
            }
        }
        return UPLOAD;
    }
}
