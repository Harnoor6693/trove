package finance.trove.categorisation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw, bank-supplied merchant string into the canonical key every lookup in this
 * package compares against — {@link Category}, {@link MerchantCategoryMap} and
 * {@link CommunityMerchantRegistry} all key on this function's output, never on the raw string,
 * so that {@code "Tim Hortons #42"} and {@code "TIM HORTONS"} resolve to the same entry.
 *
 * <p>A pure, static, stateless pipeline (no Spring bean, no I/O) so it is trivially testable and
 * safe to call from anywhere in the package, including {@code CommunityMerchantRegistry}'s own
 * load step, which normalises its JSON keys the same way rather than trusting the resource file
 * to already be in the canonical form.
 *
 * <p>Five passes, in order:
 * <ol>
 *   <li><strong>Unicode.</strong> NFKD-decomposes and strips combining marks, so an accented
 *       character folds to its base letter ({@code "café"} → {@code "CAFE"}) before anything
 *       else runs.</li>
 *   <li><strong>Apostrophes.</strong> {@code '}/{@code ’}/{@code `} are deleted outright rather
 *       than turned into a space by the punctuation pass below — {@code "Tim Horton's"} must
 *       normalise to {@code "TIM HORTONS"}, matching the community registry's and every rule's
 *       plain {@code "TIM HORTONS"}, not split into two words as {@code "TIM HORTON S"}.</li>
 *   <li><strong>Store numbers.</strong> {@code #1234} anywhere in the string is removed — the
 *       one pass the old service had.</li>
 *   <li><strong>Card/reference suffixes.</strong> A trailing run of four or more letters/digits,
 *       preceded by whitespace or {@code *}, that contains AT LEAST ONE DIGIT is removed — the
 *       reference codes a card processor or point-of-sale terminal appends ({@code "SQ *COFFEE
 *       SHOP 8842"} → {@code "SQ *COFFEE SHOP"}, {@code "AMAZON.COM*1A2B3C"} →
 *       {@code "AMAZON.COM"}). The digit requirement is load-bearing, not an incidental detail:
 *       an earlier version of this pass matched any trailing four-letter-or-longer run regardless
 *       of content, which stripped the second word off a perfectly ordinary two-word merchant
 *       name ({@code "UBER EATS"} → {@code "UBER"}) — a reference suffix a payment processor
 *       appends is, in practice, never pure letters.</li>
 *   <li><strong>Punctuation.</strong> Anything that is not a Unicode letter, digit or space
 *       becomes a space, then runs of whitespace collapse to one and the result is trimmed.</li>
 * </ol>
 *
 * <p>The whole pipeline is applied uniformly, so its own output is idempotent:
 * {@code normalise(normalise(x)).equals(normalise(x))}.
 */
public final class MerchantNormaliser {

    private static final Pattern APOSTROPHE = Pattern.compile("['’`]");
    private static final Pattern STORE_NUMBER = Pattern.compile("#\\d+");
    private static final Pattern TRAILING_REFERENCE = Pattern.compile("[\\s*]+([0-9A-Z]{4,})$");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N} ]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private MerchantNormaliser() {
    }

    /** Never {@code null}; an empty or blank input normalises to {@code ""}. */
    public static String normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = Normalizer.normalize(raw, Normalizer.Form.NFKD);
        value = COMBINING_MARKS.matcher(value).replaceAll("");
        value = value.toUpperCase(Locale.ROOT);
        value = APOSTROPHE.matcher(value).replaceAll("");
        value = STORE_NUMBER.matcher(value).replaceAll("");
        value = stripCardSuffix(value);
        value = NON_ALPHANUMERIC.matcher(value).replaceAll(" ");
        value = WHITESPACE.matcher(value).replaceAll(" ").trim();
        return value;
    }

    /** Strips a trailing reference/card suffix only when it actually contains a digit. */
    private static String stripCardSuffix(String value) {
        Matcher matcher = TRAILING_REFERENCE.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        String suffix = matcher.group(1);
        boolean hasDigit = suffix.chars().anyMatch(Character::isDigit);
        return hasDigit ? value.substring(0, matcher.start()) : value;
    }
}
