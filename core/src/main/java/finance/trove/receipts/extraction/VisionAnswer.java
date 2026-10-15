package finance.trove.receipts.extraction;

import java.math.BigDecimal;
import java.util.List;

/**
 * A vision model's answer, after every field of it has been validated
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 44's first half).
 *
 * <p>Every field is nullable and every one of them may be null even on a well-formed answer: the
 * model was asked to omit what it could not read, and a field that failed validation is dropped
 * rather than kept in a shape the code cannot justify. {@code text} is the transcription the
 * heuristic parser then reads, and it is the only field this type carries that is not itself a
 * claim about a number.
 *
 * <p>There is no currency here. The model is asked for an ISO code only if one is printed, and
 * resolving what an unlabelled {@code $} means is the server's job from the member's own row
 * (inventory D3) — so the currency is carried as {@code currencyCode} text and resolved by
 * {@link VisionAnswerParser}'s caller against {@code CurrencyCode}, never taken as a literal.
 *
 * @param merchant     the store name, or null
 * @param amount       the grand total, or null; never negative
 * @param currencyCode an ISO 4217 code the receipt actually printed, or null
 * @param rawDate      the date exactly as printed, unparsed, or null — parsing it needs the
 *                     member's country and this type does not have one
 * @param text         the transcription, bounded, or null
 * @param lineItems    validated lines; never null, possibly empty
 * @param confidence   the model's own confidence in {@code [0.000, 1.000]}, or null
 */
public record VisionAnswer(String merchant, BigDecimal amount, String currencyCode, String rawDate,
                           String text, List<LineItemCandidate> lineItems, BigDecimal confidence) {

    public VisionAnswer {
        lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
    }

    /** The answer could not be used at all. Never an exception: the fallback is manual review. */
    public static VisionAnswer unusable() {
        return new VisionAnswer(null, null, null, null, null, List.of(), null);
    }

    /** Whether anything at all came back that is worth writing to the receipt. */
    public boolean isEmpty() {
        return merchant == null && amount == null && rawDate == null && text == null
            && lineItems.isEmpty();
    }
}
