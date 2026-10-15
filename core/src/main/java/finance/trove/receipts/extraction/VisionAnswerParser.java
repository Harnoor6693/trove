package finance.trove.receipts.extraction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a vision model's reply into something the pipeline may act on, or into nothing
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 44; §2.6).
 *
 * <h2>The answer is a suggestion, never a value</h2>
 * This is somebody else's server answering about a photograph of the household's spending, and the
 * discipline row 44 states is inherited whole: strip control characters, cap the length
 * <em>before</em> parsing, validate every field, and on any failure produce nothing rather than
 * something the code cannot justify. A receipt whose extraction produced nothing becomes
 * {@code reviewRequired} with blank fields, which is the same ordinary outcome an install with no
 * AI at all gets — not an error, and not a {@code FAILED} row.
 *
 * <p>Each guard exists because its absence has a name:
 * <ul>
 *   <li><strong>Length cap first.</strong> A ten-kilobyte answer is not a receipt; parsing it
 *       before measuring it means an endpoint can decide how much work this server does.</li>
 *   <li><strong>Control characters make the whole answer a miss</strong>, not just a stripped
 *       field. The same call step 17's tier makes, for the same reason: the only use for this text
 *       is structured extraction, so refusing a tampered answer outright is strictly stronger than
 *       acting on the remains of one.</li>
 *   <li><strong>The JSON parser is built here</strong>, not injected. The application's own mapper
 *       is configured for Trove's wire contract; this is untrusted text from an endpoint the member
 *       configured, and it shares no configuration with the API.</li>
 *   <li><strong>Every number is range-checked.</strong> An amount that is negative, absurd, or not
 *       a decimal at all is dropped — the source would have written whatever came back into a
 *       {@code NUMERIC} column and discovered the problem as a constraint violation on a background
 *       thread.</li>
 *   <li><strong>Prose is a miss.</strong> A model that answered in sentences produces no JSON
 *       object, and that is the end of it; there is no attempt to find a JSON object inside prose,
 *       because "the part of the answer that looks like JSON" is an attacker-chooseable
 *       substring.</li>
 * </ul>
 *
 * <p>Nothing here logs the merchant, the text, the answer or any part of it.
 */
@Component
public class VisionAnswerParser {

    /** Longest answer parsed at all. A conforming one is a small object plus a transcription. */
    public static final int MAX_ANSWER_CHARACTERS = 8_000;

    /** {@code receipt.extracted_merchant} is {@code VARCHAR(200)}. */
    private static final int MAX_MERCHANT_CHARACTERS = 200;

    /** Bounds the transcription that reaches {@code receipt.ocr_text}. */
    private static final int MAX_TEXT_CHARACTERS = 20_000;

    /**
     * The three numeric ceilings live on {@link ExtractionLimits} because this class is not the
     * only one that parses this model's answer: the transcription it lets through is re-parsed by
     * {@link HeuristicExtractor}, and two extractors reading one untrusted answer must apply one
     * set of limits or they drift apart — which they had.
     */
    private static final int MAX_LINE_ITEMS = ExtractionLimits.MAX_LINE_ITEMS;

    private static final int MAX_LINE_DESCRIPTION_CHARACTERS = 500;

    /** No receipt total is larger than this; anything above it is a misread or a lie. */
    private static final BigDecimal MAX_AMOUNT = ExtractionLimits.MAX_AMOUNT;

    /** A quantity beyond this is a misread decimal point, not a purchase. */
    private static final BigDecimal MAX_QUANTITY = ExtractionLimits.MAX_QUANTITY;

    /**
     * Control and format characters, which a conforming answer never contains. Tab, newline and
     * carriage return are excluded: they are legal JSON whitespace and are what a transcription is
     * made of.
     */
    private static final Pattern CONTROL_CHARACTERS =
        Pattern.compile("[\\p{Cc}&&[^\\t\\n\\r]]|\\p{Cf}");

    /** An ISO 4217 code as printed. Resolved against {@code CurrencyCode} by the caller. */
    private static final Pattern ISO_CURRENCY = Pattern.compile("[A-Z]{3}");

    private static final JsonMapper PARSER = JsonMapper.builder().build();

    /**
     * Parses and validates.
     *
     * @param content the model's raw reply; null, blank, too long, prose, malformed JSON, or JSON
     *                that is not an object all yield {@link VisionAnswer#unusable()}
     * @return never null, and never anything the pipeline cannot justify writing
     */
    public VisionAnswer parse(String content) {
        if (content == null || content.isBlank()) {
            return VisionAnswer.unusable();
        }
        // Length BEFORE parse: an endpoint does not get to decide how much work this does.
        if (content.length() > MAX_ANSWER_CHARACTERS) {
            return VisionAnswer.unusable();
        }
        if (CONTROL_CHARACTERS.matcher(content).find()) {
            return VisionAnswer.unusable();
        }

        JsonNode root;
        try {
            root = PARSER.readTree(content.strip());
        } catch (JacksonException e) {
            // Prose, a markdown fence, a truncated object: all the same answer.
            return VisionAnswer.unusable();
        }
        if (root == null || !root.isObject()) {
            return VisionAnswer.unusable();
        }

        return new VisionAnswer(
            text(root, "merchant", MAX_MERCHANT_CHARACTERS),
            amount(root, "amount", MAX_AMOUNT),
            currencyCode(root),
            text(root, "date", 40),
            text(root, "text", MAX_TEXT_CHARACTERS),
            lineItems(root),
            confidence(root));
    }

    private static String text(JsonNode root, String field, int maxCharacters) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual()) {
            return null;
        }
        String value = node.asString().strip();
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > maxCharacters ? value.substring(0, maxCharacters) : value;
    }

    /**
     * A non-negative decimal below the ceiling, or null.
     *
     * <p>Accepts a JSON number or a numeric string, because OpenAI-compatible endpoints differ on
     * which they emit for the same prompt; it does not accept anything else, and it never coerces.
     */
    private static BigDecimal amount(JsonNode root, String field, BigDecimal ceiling) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        BigDecimal value;
        if (node.isNumber()) {
            value = node.decimalValue();
        } else if (node.isTextual()) {
            try {
                value = new BigDecimal(node.asString().strip().replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        if (value.signum() < 0 || value.compareTo(ceiling) > 0) {
            return null;
        }
        return value;
    }

    /** An ISO code exactly as printed, uppercased. The caller resolves it; this never guesses. */
    private static String currencyCode(JsonNode root) {
        String raw = text(root, "currency", 8);
        if (raw == null) {
            return null;
        }
        String upper = raw.toUpperCase(java.util.Locale.ROOT);
        return ISO_CURRENCY.matcher(upper).matches() ? upper : null;
    }

    private static BigDecimal confidence(JsonNode root) {
        BigDecimal value = amount(root, "confidence", BigDecimal.ONE);
        return value == null ? null : value.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private static List<LineItemCandidate> lineItems(JsonNode root) {
        JsonNode array = root.get("lineItems");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<LineItemCandidate> candidates = new ArrayList<>();
        for (JsonNode element : array) {
            if (candidates.size() >= MAX_LINE_ITEMS) {
                break;
            }
            if (!element.isObject()) {
                continue;
            }
            String description = text(element, "description", MAX_LINE_DESCRIPTION_CHARACTERS);
            if (description == null) {
                // A line with no description is not a line; there is nothing to show a member.
                continue;
            }
            BigDecimal totalPrice = amount(element, "totalPrice", MAX_AMOUNT);
            if (totalPrice == null) {
                continue;
            }
            BigDecimal quantity = amount(element, "quantity", MAX_QUANTITY);
            BigDecimal unitPrice = amount(element, "unitPrice", MAX_AMOUNT);
            JsonNode taxable = element.get("taxable");
            candidates.add(new LineItemCandidate(description,
                quantity == null || quantity.signum() == 0 ? BigDecimal.ONE : quantity,
                unitPrice == null ? BigDecimal.ZERO : unitPrice,
                totalPrice,
                taxable == null || !taxable.isBoolean() || taxable.asBoolean()));
        }
        return candidates;
    }
}
