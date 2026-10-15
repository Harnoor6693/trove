package finance.trove.ingestion;

import finance.trove.common.domain.Money;
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
}
