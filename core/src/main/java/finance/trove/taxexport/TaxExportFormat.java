package finance.trove.taxexport;

/**
 * What a {@link TaxExportRun} produces ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.9).
 *
 * <p>Both formats carry the same columns and both carry the member's own amounts and labels only
 * — no rate, no bracket, no computed figure (§9 risk 15). The source's {@code ReportType} enum
 * (MONTHLY/ANNUAL/CUSTOM) is deleted outright and has no counterpart here.
 */
public enum TaxExportFormat {

    /** The machine-readable file an accountant imports. */
    CSV("text/csv", "csv"),

    /** The same columns, rendered for printing. */
    PDF("application/pdf", "pdf");

    private final String contentType;
    private final String extension;

    TaxExportFormat(String contentType, String extension) {
        this.contentType = contentType;
        this.extension = extension;
    }

    /**
     * The {@code Content-Type} the download route sends, and the one
     * {@code openapi/trove-core.yaml} declares for that operation. The two must agree: the
     * integration suite validates every response against the document, so an undeclared content
     * type is a failing test rather than a warning.
     */
    public String contentType() {
        return contentType;
    }

    /** The extension hint handed to {@code LocalDiskStorage.put}, which re-validates it. */
    public String extension() {
        return extension;
    }
}
