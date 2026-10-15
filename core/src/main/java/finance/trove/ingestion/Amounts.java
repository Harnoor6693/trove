package finance.trove.ingestion;

import finance.trove.common.domain.Money;
import finance.trove.common.plugin.TransactionType;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * The one money-parse guard every importer reads an amount through (B1-04).
 *
 * <p>{@code new BigDecimal(text)} accepts exponents, and {@code -1E9999999} is a 292-byte OFX row
 * that costs twelve seconds of CPU the moment anything rounds it, and the heap once the exponent
 * grows. So an imported amount is a plain decimal or nothing: no exponent, a bounded length, and a
 * value that fits the {@code NUMERIC(19,4)} column ({@link Money#fitsColumn}). A {@code null}
 * answer becomes a row the preview shows with its reason, never an exception that abandons the
 * file.
 */
public final class Amounts {

    /** Longer than any amount the column can hold, written with every sign and zero it allows. */
    private static final int MAX_CHARS = 40;

    private static final Pattern PLAIN = Pattern.compile("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)");

    /** What a bank writes on a merchant's money coming back, in English or French, as a word. */
    private static final Pattern REFUND = Pattern.compile(
        "\\b(REFUND|RETURN|REMBOURSEMENT|RETOUR)(S|ED)?\\b", Pattern.CASE_INSENSITIVE);

    /** A tax refund is a household's income for the year, not a purchase that came back. */
    private static final Pattern TAX = Pattern.compile("\\b(TAX|IMP[OÔ]TS?)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private Amounts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads {@code -12.50}, {@code 12}, {@code +.5}.
     *
     * @return the value, or {@code null} when it is not a plain decimal or the column cannot hold it
     */
    public static BigDecimal parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.length() > MAX_CHARS || !PLAIN.matcher(text).matches()) {
            return null;
        }
        BigDecimal value = new BigDecimal(text);
        return Money.fitsColumn(value) ? value : null;
    }

    /**
     * The direction of a signed amount: {@code DEBIT} below zero, {@code REFUND} when one of
     * {@code texts} (the row's description, name or memo) calls it a refund or a return, and
     * {@code CREDIT} otherwise.
     *
     * <p>B1-10: every importer typed a store's refund {@code CREDIT}, which the dashboard counts as
     * income, so a $300 return inflated income and left spending unreduced. {@code REFUND} is the
     * type the dashboard and the envelopes already subtract from spending.
     *
     * <p>ponytail: a keyword match on the bank's own wording, which is all an export says. A
     * refund the bank does not label stays {@code CREDIT}; matching it to the purchase it reverses
     * is the categoriser's job once one exists.
     */
    public static TransactionType direction(BigDecimal signed, String... texts) {
        if (signed.signum() < 0) {
            return TransactionType.DEBIT;
        }
        for (String text : texts) {
            if (text != null && REFUND.matcher(text).find() && !TAX.matcher(text).find()) {
                return TransactionType.REFUND;
            }
        }
        return TransactionType.CREDIT;
    }
}
