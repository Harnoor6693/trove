package finance.trove.receipts.pipeline.stages;

import finance.trove.receipts.extraction.ExtractionLimits;
import finance.trove.receipts.extraction.LineItemCandidate;
import finance.trove.receipts.pipeline.PipelineStage;
import finance.trove.receipts.pipeline.PipelineStageException;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.LineItem;
import finance.trove.receipts.receipt.Receipt;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage 6 — the composite confidence, and the decision whether a human should look
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6; inventory receipt-processor D1 / finding M8).
 *
 * <h2>The score ceiling, which is the defect this stage exists to fix</h2>
 * The source computed
 * <pre>composite = ocr &times; 0.35 + extraction &times; 0.45 + match &times; 0.20</pre>
 * against a review threshold of 0.92 ({@code ScoringStage.java:73-91}, {@code :83-84}). A receipt
 * with no matching transaction therefore had a ceiling of 0.80 — <strong>it could never clear the
 * threshold, so its line items were never persisted and the review flag was never cleared, for any
 * unmatched receipt, ever.</strong> And "unmatched" was the normal case, because the matching call
 * itself was broken. Two weights over signals that may legitimately not exist is a ceiling
 * disguised as an average.
 *
 * <p>The fix §2.6 names is <strong>renormalisation</strong>: only the signals that were actually
 * measured take part, and their weights are divided by their own total. Concretely —
 * <ul>
 *   <li>the OCR weight is gone entirely with the OCR stage (there is no separate transcription
 *       step left to score);</li>
 *   <li>the match weight takes part only when {@link ReceiptProcessingContext#isMatchAttempted()},
 *       that is, when the household's ledger actually held a candidate in the window;</li>
 *   <li>with no candidate, extraction is the only measured signal and its weight becomes 1.0 — so
 *       a cleanly extracted, unmatched receipt scores its extraction confidence and can clear the
 *       threshold, which is exactly the regression the D1 test asserts.</li>
 * </ul>
 *
 * <h2>What clearing the threshold does</h2>
 * The line items the extraction produced are promoted to {@code line_item} rows and
 * {@code reviewRequired} is cleared. Below it, the fields stay as extracted and a human is asked —
 * which is not an error state either; it is the same place a receipt with no AI at all ends up.
 *
 * <p>The source additionally made an HTTP PATCH to the ingestion service from here, writing the
 * receipt id and a "Receipt matched: &lt;merchant&gt; $&lt;amount&gt;" string into the
 * transaction's <em>notes</em>. That is gone: the link is a column, written by
 * {@code ReceiptLinkService} at match time in one transaction with its other half, and a member's
 * own notes field is not somewhere to write machine text.
 */
@Component
public class ScoringStage implements PipelineStage {

    private static final Logger LOG = LoggerFactory.getLogger(ScoringStage.class);

    /** How much of the score the extraction carries when both signals were measured. */
    static final BigDecimal EXTRACTION_WEIGHT = new BigDecimal("0.700");

    /** How much the match carries when there was a candidate set to measure against. */
    static final BigDecimal MATCH_WEIGHT = new BigDecimal("0.300");

    /** The scale of {@code receipt.confidence}, {@code NUMERIC(4,3)}. */
    private static final int CONFIDENCE_SCALE = 3;

    private final ObjectMapper objectMapper;

    public ScoringStage(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String stageName() {
        return "SCORING";
    }

    @Override
    public void process(Receipt receipt, ReceiptProcessingContext context)
        throws PipelineStageException {

        BigDecimal composite = score(context);
        receipt.setConfidence(composite);

        if (composite.compareTo(Receipt.REVIEW_THRESHOLD) < 0) {
            receipt.setReviewRequired(true);
            return;
        }
        receipt.setReviewRequired(false);
        promoteLineItems(receipt);
    }

    /**
     * The renormalised composite.
     *
     * <p>Both weights when a candidate set existed; the extraction alone, at full weight, when
     * none did. Never a fixed denominator over signals that may not exist — that is D1.
     */
    static BigDecimal score(ReceiptProcessingContext context) {
        BigDecimal extraction = clamp(context.getExtractionConfidence());
        if (!context.isMatchAttempted()) {
            return extraction.setScale(CONFIDENCE_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal match = clamp(context.getMatchConfidence());
        BigDecimal denominator = EXTRACTION_WEIGHT.add(MATCH_WEIGHT);
        return extraction.multiply(EXTRACTION_WEIGHT).add(match.multiply(MATCH_WEIGHT))
            .divide(denominator, CONFIDENCE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return value.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : value;
    }

    /**
     * Turns the extracted line JSON into {@code line_item} rows.
     *
     * <p>Replaces rather than appends, so re-running the pipeline over a receipt cannot leave two
     * extractions' lines side by side. A JSON blob that will not parse is logged by receipt id and
     * nothing else — it is untrusted text, and the receipt's own fields are unaffected by its loss.
     */
    private void promoteLineItems(Receipt receipt) {
        if (receipt.getExtractedLineItems() == null) {
            return;
        }
        List<LineItemCandidate> candidates;
        try {
            candidates = objectMapper.readValue(receipt.getExtractedLineItems(),
                new TypeReference<List<LineItemCandidate>>() { });
        } catch (JacksonException e) {
            LOG.info("receipt-scoring: could not read the extracted line items of receipt {}",
                receipt.getId());
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        List<LineItem> rows = new ArrayList<>(candidates.size());
        for (LineItemCandidate candidate : candidates) {
            if (candidate == null || candidate.description() == null
                || candidate.totalPrice() == null) {
                continue;
            }
            // The last gate before untrusted numbers become rows. Both extractors already bound
            // what they produce against ExtractionLimits, but this JSON has been round-tripped
            // through a database column since then, and a line_item.total_price that the column
            // cannot hold would fail the write of a receipt that is otherwise finished.
            BigDecimal quantity =
                candidate.quantity() == null ? BigDecimal.ONE : candidate.quantity();
            BigDecimal unitPrice =
                candidate.unitPrice() == null ? BigDecimal.ZERO : candidate.unitPrice();
            if (!ExtractionLimits.isStorableAmount(candidate.totalPrice())
                || !ExtractionLimits.isStorableAmount(unitPrice)
                || !ExtractionLimits.isStorableQuantity(quantity)) {
                continue;
            }
            if (rows.size() >= ExtractionLimits.MAX_LINE_ITEMS) {
                break;
            }
            rows.add(new LineItem(candidate.description(), quantity, unitPrice,
                candidate.totalPrice(), candidate.taxable()));
        }
        receipt.replaceLineItems(rows);
    }
}
