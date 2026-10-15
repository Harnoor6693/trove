package finance.trove.taxexport;

/**
 * The tax line one category's amounts are exported under — the value
 * {@link TaxLineMappingService#resolve} answers with, and the seam collapse step 18's full data
 * export reads through ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, gap P2-16).
 *
 * <p><strong>{@link #none()} is not the same as an empty line.</strong> A seeded {@code NONE} row
 * says "this category has no line on either form", which is a fact the export should print. This
 * record's {@link #none()} is what a category with <em>no row at all</em> resolves to: both
 * strings empty, so the transaction is exported with empty tax-line cells and is still a row in
 * the file. A transaction that vanished from a member's tax export because nobody had written a
 * label for its category would be a silent loss, and silent losses are the worst failure this
 * step can have.
 *
 * <p>Both fields are strings a human reads off a form and {@code deductible} is an assertion the
 * seed or the member makes. Nothing multiplies any of them by anything (owner decision 2).
 *
 * @param code       the form's line code, or {@code ""} when no row exists
 * @param label      the line's wording, or {@code ""} when no row exists
 * @param deductible whether the seed or the member asserts the line is claimable
 * @param overridden {@code true} when this came from the caller's own row rather than the seed
 */
public record TaxLine(String code, String label, boolean deductible, boolean overridden) {

    private static final TaxLine NONE = new TaxLine("", "", false, false);

    /** The answer for a category no row maps: empty cells, still exported. */
    public static TaxLine none() {
        return NONE;
    }

    /** Whether this line came from a stored row at all. */
    public boolean isMapped() {
        return !code.isEmpty();
    }
}
