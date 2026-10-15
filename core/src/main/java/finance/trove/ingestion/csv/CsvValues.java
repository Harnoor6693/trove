package finance.trove.ingestion.csv;

import finance.trove.ingestion.Amounts;
import finance.trove.ingestion.DateFormatCandidate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading the two cell types a CSV export gets wrong in interesting ways: money and dates.
 *
 * <p>Nothing here is generic parsing for its own sake — every case is something a real Canadian or
 * American bank export actually contains.
 *
 * <h2>Money</h2>
 * Thousands separators ({@code 1,234.56}), a decimal comma ({@code 1 234,56}), a currency symbol
 * or code ({@code $1,234.56}, {@code 12.34 CAD}), a non-breaking space between the symbol and the
 * digits, an accounting negative in parentheses ({@code (42.00)}), a trailing minus
 * ({@code 42.00-}) and a card statement's {@code CR}/{@code DR}. Everything is read into a
 * {@link BigDecimal}: §9 risk 6 makes a binary float in the ledger an architecture violation, and
 * the reason is that IEEE-754 cannot represent {@code 0.10} at all.
 *
 * <h2>Dates, and the one question the data cannot answer</h2>
 * {@code 03/09/2026} is the third of September in Canada and the ninth of March in the United
 * States. Both readings produce a valid date for every row of a file that happens to contain only
 * day-of-month values of twelve or less, so no amount of sniffing can decide it — which is exactly
 * why {@link #dateFormatCandidates} exists. A file that contains one unambiguous cell
 * ({@code 13/09/2026}) settles the question for the whole file; a file that does not offers the
 * member both readings with the sample values that make them ambiguous, and the commit uses what
 * comes back. Guessing silently is how a household's September spending lands in March.
 */
final class CsvValues {

    /** Unambiguous patterns, tried in order before the two that need the member. */
    private static final List<String> UNAMBIGUOUS_DATE_PATTERNS = List.of(
        "uuuu-MM-dd", "uuuu/MM/dd", "uuuuMMdd", "d MMM uuuu", "MMM d uuuu", "d MMMM uuuu");

    /** Day-first, then month-first. The order is the order the candidates are offered in. */
    static final String DAY_FIRST_SLASH = "dd/MM/uuuu";

    static final String MONTH_FIRST_SLASH = "MM/dd/uuuu";

    static final String DAY_FIRST_DASH = "dd-MM-uuuu";

    static final String MONTH_FIRST_DASH = "MM-dd-uuuu";

    /**
     * The same two readings for a file whose day and month are NOT zero-padded
     * ({@code 3/9/2026}), which plenty of Canadian and American exports emit
     * (confirmed review finding 4).
     *
     * <p>They are a separate, later pair rather than a replacement for the padded ones because
     * {@code d} accepts one digit OR two: {@code d/M/uuuu} reads a padded file perfectly well, so
     * promoting it would silently rename the format offered for every ordinary file — and a
     * member's stored {@code mapping_json} says {@code dd/MM/uuuu}. Tried after the padded pair,
     * it changes nothing for a padded file and is the only pair that fits an unpadded one.
     *
     * <p>Without them {@code detectDateFormat} returned null AND {@code dateFormatCandidates}
     * returned empty for such a file: every row came back INVALID with the message "this row's
     * date could not be read as null", and the commit told the member to choose from an empty
     * list.
     */
    static final String DAY_FIRST_SLASH_UNPADDED = "d/M/uuuu";

    static final String MONTH_FIRST_SLASH_UNPADDED = "M/d/uuuu";

    static final String DAY_FIRST_DASH_UNPADDED = "d-M-uuuu";

    static final String MONTH_FIRST_DASH_UNPADDED = "M-d-uuuu";

    /**
     * The ambiguous readings, in the order they are tried and offered.
     *
     * <p>Padded before unpadded, so a file that fits both is described the way it is written.
     */
    private static final List<String[]> AMBIGUOUS_PAIRS = List.of(
        new String[] {DAY_FIRST_SLASH, MONTH_FIRST_SLASH},
        new String[] {DAY_FIRST_DASH, MONTH_FIRST_DASH},
        new String[] {DAY_FIRST_SLASH_UNPADDED, MONTH_FIRST_SLASH_UNPADDED},
        new String[] {DAY_FIRST_DASH_UNPADDED, MONTH_FIRST_DASH_UNPADDED});

    /** {@code 45.00 DR}: the amount, then a card statement's direction marker. */
    private static final Pattern CREDIT_DEBIT_SUFFIX =
        Pattern.compile("(.*[\\d)])\\s*(CR|DR)", Pattern.CASE_INSENSITIVE);

    /** A three-letter code before or after the number, or the letters of {@code US$}/{@code C$}. */
    private static final Pattern CURRENCY_CODE =
        Pattern.compile("^[A-Za-z]{3}(?![A-Za-z])|(?<![A-Za-z])[A-Za-z]{3}$|^[A-Za-z]{1,2}(?=\\$)");

    private CsvValues() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads one money cell.
     *
     * <p>B1-01: the decimal separator is decided by one deterministic rule, because a Quebec export
     * writes {@code 12,50} and reading that as {@code 1250} imports every amount a hundred times
     * too large. When a cell has both {@code .} and {@code ,}, the LAST one is the decimal point
     * ({@code 1,234.56}, {@code 1.234,56}). When it has only commas, the last comma is the decimal
     * point if exactly one or two digits follow it ({@code 12,50}, {@code 12,5}), and every comma
     * is a thousands separator otherwise ({@code 1,234}). A trailing {@code CR} or {@code DR} is
     * the sign. Letters are a currency code ({@code CAD 12.34}, {@code US$12.34}) or the cell is not
     * an amount: {@code 1.5e3} used to read as {@code 1.53}. Whatever is left goes through
     * {@link Amounts#parse}, the guard every importer shares.
     *
     * @return the value, or {@code null} when the cell is blank or is not a number at all
     */
    static BigDecimal amount(String cell) {
        if (cell == null) {
            return null;
        }
        String text = cell.trim();
        Matcher suffix = CREDIT_DEBIT_SUFFIX.matcher(text);
        Boolean credit = null;
        if (suffix.matches()) {
            credit = "CR".equalsIgnoreCase(suffix.group(2));
            text = suffix.group(1).trim();
        }
        text = CURRENCY_CODE.matcher(text).replaceFirst("").trim();
        if (text.isEmpty()) {
            return null;
        }
        boolean negative = false;
        boolean signed = false;
        if (text.startsWith("(") && text.endsWith(")")) {
            negative = true;
            text = text.substring(1, text.length() - 1).trim();
        }
        if (text.endsWith("-")) {
            negative = true;
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.startsWith("-")) {
            negative = true;
            text = text.substring(1).trim();
        } else if (text.startsWith("+")) {
            signed = true;
            text = text.substring(1).trim();
        }
        if (credit != null) {
            if (negative || signed) {
                // "-45.00 CR" says two things about the direction; neither is guessed.
                return null;
            }
            negative = !credit;
        }
        StringBuilder number = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9' || c == '.' || c == ',') {
                number.append(c);
            } else if (c == ' ' || c == '\u00A0' || c == '\u202F' || c == '\''
                || c == '$' || c == '€' || c == '£') {
                // A grouping space or apostrophe, or a currency symbol. The row's currency comes
                // from the mapped currency column or from the account, never a symbol (F4).
                continue;
            } else {
                return null;
            }
        }
        BigDecimal value = Amounts.parse(withDecimalPoint(number.toString()));
        return value == null || !negative ? value : value.negate();
    }

    /** {@code digits} with {@code .} as its decimal point and no grouping; garbage stays garbage. */
    private static String withDecimalPoint(String digits) {
        int dot = digits.lastIndexOf('.');
        int comma = digits.lastIndexOf(',');
        int fraction = digits.length() - comma - 1;
        if (comma <= dot || dot < 0 && (fraction < 1 || fraction > 2)) {
            // 1234, 1,234.56 and 1,234: no comma at all, or every comma groups thousands.
            return digits.replace(",", "");
        }
        // 1.234,56 and 12,50: the last comma is the decimal point, dots before it group thousands.
        String whole = digits.substring(0, comma);
        if (dot >= 0 && whole.indexOf(',') >= 0) {
            return digits; // 1.234,567,89: no reading of this is a number
        }
        return whole.replace(".", "").replace(",", "") + "." + digits.substring(comma + 1);
    }

    /** Parses {@code cell} under {@code pattern}, or {@code null} if it does not fit. */
    static LocalDate date(String cell, String pattern) {
        if (cell == null || pattern == null) {
            return null;
        }
        String text = cell.trim().replace(',', ' ').replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(text, formatter(pattern));
        } catch (DateTimeParseException e) {
            // swallow-ok: "does not fit this pattern" is the question being asked.
            return null;
        }
    }

    /**
     * The pattern that reads every one of {@code cells}, or {@code null} when the file is
     * ambiguous (or unreadable) and {@link #dateFormatCandidates} has to ask.
     */
    static String detectDateFormat(List<String> cells) {
        for (String pattern : UNAMBIGUOUS_DATE_PATTERNS) {
            if (readsEveryCell(cells, pattern)) {
                return pattern;
            }
        }
        for (String[] pair : AMBIGUOUS_PAIRS) {
            boolean dayFirst = readsEveryCell(cells, pair[0]);
            boolean monthFirst = readsEveryCell(cells, pair[1]);
            if (dayFirst != monthFirst) {
                return dayFirst ? pair[0] : pair[1];
            }
        }
        return null;
    }

    /**
     * The readings a member has to choose between, or empty when the file is not ambiguous.
     *
     * <p>Non-empty exactly when {@link #detectDateFormat} returned {@code null} because BOTH
     * readings fit every cell. A file nothing reads at all produces no candidates either — that is
     * an unmappable date column, not a choice.
     */
    static List<DateFormatCandidate> dateFormatCandidates(List<String> cells, int sampleSize) {
        for (String[] pair : AMBIGUOUS_PAIRS) {
            if (readsEveryCell(cells, pair[0]) && readsEveryCell(cells, pair[1])) {
                List<String> samples = samples(cells, sampleSize);
                List<DateFormatCandidate> candidates = new ArrayList<>(2);
                for (String pattern : pair) {
                    candidates.add(new DateFormatCandidate(pattern, samples,
                        samples.stream().map(cell -> date(cell, pattern)).toList()));
                }
                return List.copyOf(candidates);
            }
        }
        return List.of();
    }

    private static List<String> samples(List<String> cells, int sampleSize) {
        Set<String> distinct = new LinkedHashSet<>(cells);
        return distinct.stream().limit(Math.max(1, sampleSize)).toList();
    }

    private static boolean readsEveryCell(List<String> cells, String pattern) {
        if (cells.isEmpty()) {
            return false;
        }
        for (String cell : cells) {
            if (date(cell, pattern) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@link ResolverStyle#STRICT} with {@code uuuu} rather than {@code yyyy}, so that
     * {@code 13/09/2026} is REFUSED by {@code MM/dd/uuuu} instead of being smoothed into a valid
     * date. That refusal is the whole mechanism by which one unambiguous row settles the format
     * for a file; a lenient resolver accepts both readings of every row and the ambiguity becomes
     * undetectable.
     */
    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
            .withResolverStyle(ResolverStyle.STRICT);
    }
}
