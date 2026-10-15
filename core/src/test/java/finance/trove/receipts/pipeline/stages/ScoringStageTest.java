package finance.trove.receipts.pipeline.stages;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptSource;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The score-ceiling regression (inventory receipt-processor D1 / finding M8).
 *
 * <p><strong>The first test is the gate and it fails without the fix.</strong> Under the source's
 * arithmetic — {@code ocr &times; 0.35 + extraction &times; 0.45 + match &times; 0.20} against a
 * 0.92 threshold — a receipt with a perfect extraction and no matching transaction scored 0.45 and
 * could not reach 0.92 by any route. Line items were therefore never persisted and the review flag
 * was never cleared, for any unmatched receipt, ever; and "unmatched" was the normal case, because
 * the matching call itself was broken.
 */
class ScoringStageTest {

    private final ScoringStage stage = new ScoringStage(JsonMapper.builder().build());

    private static ReceiptProcessingContext context() {
        return new ReceiptProcessingContext(Jurisdiction.CA, "ON", CurrencyCode.CAD);
    }

    private static Receipt receipt() {
        return new Receipt(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
            ReceiptSource.UPLOAD, 1_000L);
    }

    @Test
    @DisplayName("D1: an unmatched receipt with a clean extraction reaches the review threshold")
    void unmatchedReceiptsCanClearTheThreshold() throws Exception {
        ReceiptProcessingContext context = context();
        context.setExtractionConfidence(new BigDecimal("0.950"));
        // No candidate existed at all. Under the old fixed denominator this receipt's ceiling was
        // 0.80 and the threshold 0.92, so it could never clear it and its line items were never
        // written. Renormalising over the signals that were actually measured is the fix.
        context.setMatchAttempted(false);

        Receipt receipt = receipt();
        receipt.setExtractedLineItems("""
            [{"description":"Latte","quantity":1,"unitPrice":5.75,"totalPrice":5.75,\
"taxable":true}]""");

        stage.process(receipt, context);

        assertThat(receipt.getConfidence()).isEqualByComparingTo(new BigDecimal("0.950"));
        assertThat(receipt.getConfidence())
            .as("an unmatched receipt must be able to clear the review threshold")
            .isGreaterThanOrEqualTo(Receipt.REVIEW_THRESHOLD);
        assertThat(receipt.isReviewRequired()).isFalse();
        assertThat(receipt.getLineItems())
            .as("the line items must be persisted once the threshold is cleared — the thing D1 "
                + "made impossible")
            .hasSize(1);
        assertThat(receipt.getLineItems().get(0).getDescription()).isEqualTo("Latte");
    }

    @Test
    @DisplayName("with a candidate set, both weights count and the composite is their average")
    void bothWeightsCountWhenAMatchWasAttempted() throws Exception {
        ReceiptProcessingContext context = context();
        context.setExtractionConfidence(new BigDecimal("1.000"));
        context.setMatchAttempted(true);
        context.setMatchConfidence(new BigDecimal("1.000"));

        Receipt receipt = receipt();
        stage.process(receipt, context);

        assertThat(receipt.getConfidence()).isEqualByComparingTo(new BigDecimal("1.000"));
        assertThat(receipt.isReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("a weak match drags the composite below the threshold and asks for review")
    void aWeakMatchAsksForReview() throws Exception {
        ReceiptProcessingContext context = context();
        context.setExtractionConfidence(new BigDecimal("1.000"));
        context.setMatchAttempted(true);
        context.setMatchConfidence(new BigDecimal("0.800"));

        Receipt receipt = receipt();
        stage.process(receipt, context);

        // 1.000 * 0.7 + 0.800 * 0.3 = 0.940 ... still above. Use a weaker extraction to cross.
        assertThat(receipt.getConfidence()).isEqualByComparingTo(new BigDecimal("0.940"));

        ReceiptProcessingContext weaker = context();
        weaker.setExtractionConfidence(new BigDecimal("0.600"));
        weaker.setMatchAttempted(true);
        weaker.setMatchConfidence(new BigDecimal("0.800"));
        Receipt second = receipt();
        stage.process(second, weaker);

        assertThat(second.getConfidence()).isLessThan(Receipt.REVIEW_THRESHOLD);
        assertThat(second.isReviewRequired()).isTrue();
        assertThat(second.getLineItems()).isEmpty();
    }

    @Test
    @DisplayName("a poor extraction asks for review even with nothing to match against")
    void aPoorExtractionStillAsksForReview() throws Exception {
        ReceiptProcessingContext context = context();
        context.setExtractionConfidence(new BigDecimal("0.500"));
        context.setMatchAttempted(false);

        Receipt receipt = receipt();
        stage.process(receipt, context);

        assertThat(receipt.getConfidence()).isEqualByComparingTo(new BigDecimal("0.500"));
        assertThat(receipt.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("confidences outside [0,1] are clamped rather than trusted")
    void confidencesAreClamped() {
        ReceiptProcessingContext high = context();
        high.setExtractionConfidence(new BigDecimal("5.000"));
        high.setMatchAttempted(false);
        assertThat(ScoringStage.score(high)).isEqualByComparingTo(BigDecimal.ONE);

        ReceiptProcessingContext low = context();
        low.setExtractionConfidence(new BigDecimal("-2.000"));
        low.setMatchAttempted(false);
        assertThat(ScoringStage.score(low)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("unparseable line-item JSON loses the lines, not the receipt")
    void unparseableLineItemsAreSurvivable() throws Exception {
        ReceiptProcessingContext context = context();
        context.setExtractionConfidence(BigDecimal.ONE);
        context.setMatchAttempted(false);

        Receipt receipt = receipt();
        receipt.setExtractedLineItems("not json at all");

        stage.process(receipt, context);

        assertThat(receipt.isReviewRequired()).isFalse();
        assertThat(receipt.getLineItems()).isEmpty();
    }
}
