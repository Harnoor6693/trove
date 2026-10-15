package finance.trove.receipts.extraction;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Regex extraction of merchant, amount, currency, date and line items from receipt text
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6; inventory receipt-processor D2 and D3).
 *
 * <p>The source's rules are kept. <strong>Its two currency and date defects are not</strong>, and
 * both were the same mistake — a per-user fact hard-coded as a constant:
 *
 * <h2>D2 — the date format order comes from the member's country</h2>
 * {@code HeuristicExtractor.java:107-113} tried exactly one slash format, {@code MM/dd/yyyy}. A
 * Canadian member's receipt reading {@code 05/06/2026} was therefore parsed as 6 May when it says
 * 5 June, silently, and the wrong date is the field the whole matching stage searches on. Here the
 * order is taken from {@link Jurisdiction}: {@code CA} tries day-first then month-first, {@code US}
 * the other way round. Unambiguous dates ({@code 25/12/2026}) parse the same either way; ambiguous
 * ones follow the member. A member with no country falls back to ISO only, because guessing
 * between the two orders is exactly what this fixes.
 *
 * <h2>D3 — the currency comes from the member, never from a literal</h2>
 * {@code HeuristicExtractor.java:154} returned a hard-coded Canadian dollar code for any text
 * merely containing a {@code $}, so every American member's coffee was Canadian. Here an explicit
 * ISO code in the text wins; failing that the uploader's own {@code users.currency} is used;
 * failing that the answer is null. There is no currency literal anywhere in this class — {@code ArchitectureTest} bans the
 * strings outside {@link CurrencyCode} in any case (§9 risk 5).
 */
@Component
public class HeuristicExtractor {

    /** Confidence contributions, summing to 1.000 when every field is found. */
    private static final BigDecimal MERCHANT_WEIGHT = new BigDecimal("0.250");
    private static final BigDecimal AMOUNT_WEIGHT = new BigDecimal("0.350");
    private static final BigDecimal DATE_WEIGHT = new BigDecimal("0.250");
    private static final BigDecimal LINE_ITEM_WEIGHT = new BigDecimal("0.150");

    /** Longest text this class will look at at all; anything past it is not a receipt. */
    private static final int MAX_TEXT_CHARACTERS = 100_000;

    /** {@code receipt.extracted_merchant} is {@code VARCHAR(200)}. */
    private static final int MAX_MERCHANT_CHARACTERS = 200;

    /** A single price line is far more likely to be the total than an item list. */
    private static final int MIN_LINE_ITEMS = 2;

    private static final Pattern TOTAL_PATTERN = Pattern.compile(
        "(?:TOTAL|SUBTOTAL|AMOUNT DUE|AMOUNT|BALANCE DUE|GRAND TOTAL)[:\\s]*\\$?"
            + "([0-9,]+\\.[0-9]{2})",
        Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private static final Pattern LINE_ITEM_PATTERN = Pattern.compile(
        "^(.+?)\\s{2,}\\$?([0-9]+\\.[0-9]{2})\\s*$", Pattern.MULTILINE);

    private static final Pattern DATE_ISO = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern DATE_SLASH = Pattern.compile("(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4})");
    private static final Pattern DATE_WRITTEN = Pattern.compile("([A-Za-z]+ \\d{1,2},? \\d{4})");
    private static final Pattern DATE_DMY = Pattern.compile("(\\d{1,2} [A-Za-z]+ \\d{4})");

    private static final Pattern ANY_DATE = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{4}");
    private static final Pattern ANY_MONEY = Pattern.compile("\\$?[0-9]+\\.[0-9]{2}");

    /** An explicit ISO 4217 code standing on its own, not part of a longer word. */
    private static final Pattern EXPLICIT_CURRENCY =
        Pattern.compile("\\b([A-Z]{3})\\b");

    /**
     * Reads whatever it can.
     *
     * @param text     the extracted text; null or blank yields {@link HeuristicExtractionResult#empty}
     * @param country  the uploader's country, deciding the slash-date order (D2); may be null
     * @param fallback the uploader's own currency, used when the text names no ISO code (D3); may
     *                 be null, in which case an unlabelled receipt has no currency rather than a
     *                 guessed one
     */
    public HeuristicExtractionResult extract(String text, Jurisdiction country,
                                             CurrencyCode fallback) {
        if (text == null || text.isBlank()) {
            return HeuristicExtractionResult.empty();
        }
        String bounded = text.length() > MAX_TEXT_CHARACTERS
            ? text.substring(0, MAX_TEXT_CHARACTERS) : text;

        String merchant = extractMerchant(bounded);
        BigDecimal amount = extractAmount(bounded);
        LocalDate date = extractDate(bounded, country);
        CurrencyCode currency = extractCurrency(bounded, fallback);
        List<LineItemCandidate> lineItems = extractLineItems(bounded);

        BigDecimal confidence = BigDecimal.ZERO;
        if (merchant != null) {
            confidence = confidence.add(MERCHANT_WEIGHT);
        }
        if (amount != null) {
            confidence = confidence.add(AMOUNT_WEIGHT);
        }
        if (date != null) {
            confidence = confidence.add(DATE_WEIGHT);
        }
        if (!lineItems.isEmpty()) {
            confidence = confidence.add(LINE_ITEM_WEIGHT);
        }

        return new HeuristicExtractionResult(merchant, amount, currency, date, lineItems,
            confidence);
    }

    /**
     * The largest keyword-labelled figure that this server would be willing to store, which avoids
     * picking a subtotal over the total.
     *
     * <p><strong>The ceiling is not decoration.</strong> This method reads
     * {@code receipt.ocr_text}, which is a transcription written by a vision model and is
     * therefore untrusted text, and what it returns is written to
     * {@code receipt.extracted_amount}, {@code NUMERIC(19,4)}. A "total" of sixteen or more
     * integer digits parses perfectly well and is then refused by Postgres — and a refusal at that
     * depth is not a bad field but a receipt that can never reach a terminal status, because the
     * failure path has to save the same entity again. An over-large candidate is simply not a
     * total: it is skipped here, exactly as {@link VisionAnswerParser} already skips one in the
     * model's structured fields, and against the same {@link ExtractionLimits#MAX_AMOUNT}.
     */
    private static BigDecimal extractAmount(String text) {
        Matcher matcher = TOTAL_PATTERN.matcher(text);
        BigDecimal largest = null;
        while (matcher.find()) {
            try {
                BigDecimal candidate = new BigDecimal(matcher.group(1).replace(",", ""));
                if (!ExtractionLimits.isStorableAmount(candidate)) {
                    // Not a total: no receipt is for a billion, and the column cannot hold it.
                    continue;
                }
                if (largest == null || candidate.compareTo(largest) > 0) {
                    largest = candidate;
                }
            } catch (NumberFormatException e) {
                // A group of digits and one dot that BigDecimal still refuses is not a total.
                continue;
            }
        }
        return largest;
    }

    /**
     * ISO first, then the member's own slash order, then the written forms.
     *
     * <p>ISO is unconditional and first because {@code yyyy-MM-dd} is unambiguous in every country
     * and a receipt that prints it has settled the question itself.
     */
    private static LocalDate extractDate(String text, Jurisdiction country) {
        LocalDate iso = firstMatch(text, DATE_ISO, raw -> LocalDate.parse(raw));
        if (iso != null) {
            return iso;
        }

        LocalDate slash = firstMatch(text, DATE_SLASH, raw -> parseSlashDate(raw, country));
        if (slash != null) {
            return slash;
        }

        LocalDate written = firstMatch(text, DATE_WRITTEN,
            raw -> parseWith(raw.replace(",", ""), "MMMM d yyyy", "MMM d yyyy"));
        if (written != null) {
            return written;
        }
        return firstMatch(text, DATE_DMY, raw -> parseWith(raw, "d MMMM yyyy", "d MMM yyyy"));
    }

    /**
     * Parses one date string the way {@code country}'s members write dates.
     *
     * <p>Public because the vision stage needs exactly this and must not grow a second copy of it.
     * The model is asked to copy the date <em>as printed</em> precisely because it does not know
     * whose receipt it is looking at; the order that turns those characters into a day is the
     * member's own (inventory D2). One implementation, two callers.
     *
     * @param raw     the date exactly as printed, or null
     * @param country the uploader's country; null yields ISO and the written forms only
     * @return the parsed date, or null when nothing matched
     */
    public static LocalDate parseDate(String raw, Jurisdiction country) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return extractDate(raw.strip(), country);
    }

    /**
     * The D2 fix, and the whole of it: the ORDER is the member's, not a constant.
     *
     * <p>A member with no country gets no slash date at all rather than an American one — the
     * matching stage searching on a date five months out is worse than searching on no date.
     */
    private static LocalDate parseSlashDate(String raw, Jurisdiction country) {
        if (country == null) {
            return null;
        }
        String normalised = raw.replace('.', '/').replace('-', '/');
        String[] order = switch (country) {
            case CA -> new String[] {"d/M/yyyy", "M/d/yyyy"};
            case US -> new String[] {"M/d/yyyy", "d/M/yyyy"};
        };
        return parseWith(normalised, order);
    }

    private static LocalDate parseWith(String raw, String... patterns) {
        for (String pattern : patterns) {
            try {
                return LocalDate.parse(raw,
                    DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
            } catch (DateTimeParseException e) {
                // Try the next format; a receipt that matches none has no date.
                continue;
            }
        }
        return null;
    }

    private static LocalDate firstMatch(String text, Pattern pattern,
                                        java.util.function.Function<String, LocalDate> parse) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            try {
                LocalDate parsed = parse.apply(matcher.group(1));
                if (parsed != null) {
                    return parsed;
                }
            } catch (DateTimeParseException e) {
                // A run of digits shaped like a date that is not one, e.g. 2026-99-99.
                continue;
            }
        }
        return null;
    }

    /**
     * The D3 fix: an explicit ISO code, else the member's own currency, else nothing.
     *
     * <p>A bare {@code $} says nothing about which dollar it is, and the source's answer to that
     * question was a literal.
     */
    private static CurrencyCode extractCurrency(String text, CurrencyCode fallback) {
        Matcher matcher = EXPLICIT_CURRENCY.matcher(text.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            for (CurrencyCode candidate : CurrencyCode.values()) {
                if (candidate.name().equals(matcher.group(1))) {
                    return candidate;
                }
            }
        }
        return fallback;
    }

    /** The first line that is neither a date nor a price, capped at the column's width. */
    private static String extractMerchant(String text) {
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.length() >= 4
                && !ANY_DATE.matcher(trimmed).find()
                && !ANY_MONEY.matcher(trimmed).find()) {
                return trimmed.length() > MAX_MERCHANT_CHARACTERS
                    ? trimmed.substring(0, MAX_MERCHANT_CHARACTERS) : trimmed;
            }
        }
        return null;
    }

    /**
     * The price lines, bounded in both directions.
     *
     * <p>Each price goes to {@code line_item.total_price}, {@code NUMERIC(19,4)}, so a figure over
     * {@link ExtractionLimits#MAX_AMOUNT} is dropped for the same reason
     * {@link #extractAmount} drops one — and the list itself is capped at
     * {@link ExtractionLimits#MAX_LINE_ITEMS}, which {@link VisionAnswerParser} already applies to
     * the model's structured lines and which this side of the same untrusted answer lacked: a
     * 20,000 character transcription of nothing but two-space-separated prices would otherwise
     * have become thousands of rows.
     */
    private static List<LineItemCandidate> extractLineItems(String text) {
        List<LineItemCandidate> items = new ArrayList<>();
        Matcher matcher = LINE_ITEM_PATTERN.matcher(text);
        while (matcher.find()) {
            if (items.size() >= ExtractionLimits.MAX_LINE_ITEMS) {
                break;
            }
            try {
                BigDecimal price = new BigDecimal(matcher.group(2));
                if (!ExtractionLimits.isStorableAmount(price)) {
                    continue;
                }
                items.add(LineItemCandidate.of(matcher.group(1).strip(), price));
            } catch (NumberFormatException e) {
                continue;
            }
        }
        return items.size() >= MIN_LINE_ITEMS ? items : List.of();
    }
}
