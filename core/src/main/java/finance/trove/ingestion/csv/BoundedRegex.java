package finance.trove.ingestion.csv;

import java.util.regex.Pattern;

/**
 * Runs a header-guess regex under a hard step budget
 * ({@code docs/recovery/inventory/plugins.md} §8.2's ReDoS note).
 *
 * <p><strong>Why not the pre-collapse {@code ReDoSGuard}.</strong> That class submitted the match
 * to an executor and gave up on the {@code Future} after a timeout — and its own javadoc admits
 * the flaw: {@code Matcher.find()} never checks the interrupt flag, so the runaway match keeps
 * running on a pool thread until it finishes naturally. Only the caller was unblocked. A file with
 * a few dozen crafted headers therefore parks a few dozen threads for as long as the backtracking
 * lasts, which is the denial of service the guard was written to prevent.
 *
 * <p>This one bounds the match itself. The regex engine reads its input through
 * {@link CharSequence#charAt}, so a counting wrapper that throws once the budget is spent aborts
 * the match <em>inside</em> the engine, on the calling thread, with no pool and nothing left
 * running. A budget is spent per match rather than per file so that one pathological header cannot
 * consume the allowance of the ones after it.
 *
 * <p>An exhausted budget is a non-match, not an error. The header simply fails to be recognised as
 * that field, the member maps the column by hand, and the request answers in bounded time.
 */
final class BoundedRegex {

    /**
     * How many characters the engine may read for one header match.
     *
     * <p>A header is a few dozen characters, and the patterns here are anchored alternations of
     * literals, so a legitimate match costs a few hundred steps at most. Fifty thousand is four
     * orders of magnitude of headroom and still bounded.
     */
    static final int STEP_BUDGET = 50_000;

    private BoundedRegex() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Whether {@code input} matches {@code pattern} within the budget. Over budget is no match. */
    static boolean matches(Pattern pattern, String input) {
        if (input == null) {
            return false;
        }
        try {
            return pattern.matcher(new BoundedCharSequence(input, STEP_BUDGET)).matches();
        } catch (BudgetExhausted e) {
            // swallow-ok: the budget is a bound on work, not an error condition. The header is
            // reported as unrecognised, which is exactly what an unmappable header is, and the
            // member maps the column by hand.
            return false;
        }
    }

    /** Thrown from inside the regex engine to abort a match that has read too much. */
    private static final class BudgetExhausted extends RuntimeException {

        private static final long serialVersionUID = 1L;

        BudgetExhausted() {
            super("regex step budget exhausted", null, false, false);
        }
    }

    /**
     * The input as the engine sees it, counting every read.
     *
     * <p>{@link #subSequence} shares the parent's counter deliberately: a pattern that takes
     * sub-sequences must not be able to reset its own allowance.
     */
    private static final class BoundedCharSequence implements CharSequence {

        private final CharSequence delegate;
        private final int[] remaining;

        BoundedCharSequence(CharSequence delegate, int budget) {
            this(delegate, new int[] {budget});
        }

        private BoundedCharSequence(CharSequence delegate, int[] remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (--remaining[0] < 0) {
                throw new BudgetExhausted();
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BoundedCharSequence(delegate.subSequence(start, end), remaining);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
