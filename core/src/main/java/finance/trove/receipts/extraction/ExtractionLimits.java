package finance.trove.receipts.extraction;

import java.math.BigDecimal;

/**
 * The ceilings every extracted number is measured against, in ONE place
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 44; §2.6's "the model's answer is a suggestion").
 *
 * <p><strong>Why this class exists rather than a constant on each extractor.</strong>
 * {@link VisionAnswerParser} range-checked the numbers it took from the model's structured fields,
 * and {@link HeuristicExtractor} — which re-parses the same model's free-text transcription out of
 * {@code receipt.ocr_text} — checked nothing at all. Both write to the same columns:
 * {@code receipt.extracted_amount} and {@code line_item.total_price} are {@code NUMERIC(19,4)},
 * whose largest absolute value is just under 10<sup>15</sup>. A transcription carrying a sixteen
 * digit "total" therefore parsed cleanly, reached the database, and was refused there — and a
 * refusal at that depth is not a bad field, it is a receipt that can never reach a terminal status
 * and is re-enqueued by the hourly sweep for ever. Two extractors reading one untrusted answer
 * must apply one set of limits, and a constant that lives on only one of them is how they drifted
 * apart in the first place.
 *
 * <p>The numbers are deliberately far above any real receipt and far below what the column can
 * hold, so that a value refused here is always a misread or a lie and never a purchase.
 */
public final class ExtractionLimits {

    /**
     * No receipt total is larger than this; anything above it is a misread or a lie.
     *
     * <p>Ten orders of magnitude below the {@code NUMERIC(19,4)} ceiling, so a value that passes
     * here can always be stored, and every arithmetic the tax split performs on it stays inside
     * the column too.
     */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000");

    /** A quantity beyond this is a misread decimal point, not a purchase. */
    public static final BigDecimal MAX_QUANTITY = new BigDecimal("1000000");

    /** A receipt with more lines than this is not a receipt. */
    public static final int MAX_LINE_ITEMS = 200;

    private ExtractionLimits() {
    }

    /**
     * Whether a money figure is one this server is willing to write.
     *
     * @param value the parsed figure, or null
     * @return true when it is non-null, not negative and at most {@link #MAX_AMOUNT}
     */
    public static boolean isStorableAmount(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(MAX_AMOUNT) <= 0;
    }

    /**
     * Whether a quantity is one this server is willing to write.
     *
     * @param value the parsed quantity, or null
     * @return true when it is non-null, not negative and at most {@link #MAX_QUANTITY}
     */
    public static boolean isStorableQuantity(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(MAX_QUANTITY) <= 0;
    }
}
