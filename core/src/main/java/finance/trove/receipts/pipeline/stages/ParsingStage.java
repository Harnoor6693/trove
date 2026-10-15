package finance.trove.receipts.pipeline.stages;

import finance.trove.receipts.extraction.HeuristicExtractionResult;
import finance.trove.receipts.extraction.HeuristicExtractor;
import finance.trove.receipts.extraction.LineItemCandidate;
import finance.trove.receipts.pipeline.PipelineStage;
import finance.trove.receipts.pipeline.PipelineStageException;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.Receipt;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage 3 — regex parsing over whatever text the vision model transcribed
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6; inventory receipt-processor D2 and D3).
 *
 * <p><strong>It runs after extraction now, not before it.</strong> In the source the order was
 * OCR &rarr; PARSING &rarr; EXTRACTING: the heuristics ran on PaddleOCR's text and the AI was a
 * fallback for when they scored badly. With the OCR service deleted there is no text until the
 * vision model has produced some, so parsing is what reads it — and it fills the fields the model
 * did not answer with rather than competing with the ones it did.
 *
 * <p><strong>Nothing here overwrites a field the vision stage already set.</strong> The model saw
 * the image; the regexes only see its transcription, so where the two could disagree the model is
 * the better witness. What the regexes are for is the fields it omitted — and, for the currency,
 * the resolution the model was deliberately not asked to make.
 *
 * <p>The two per-member facts come from the context, never from a constant:
 * <ul>
 *   <li>the slash-date <em>order</em> from the uploader's {@code users.country}, so the same
 *       {@code 05/06/2026} is 5 June for a Canadian member and 6 May for an American one
 *       (inventory D2);</li>
 *   <li>the currency from the uploader's {@code users.currency} unless the receipt printed an ISO
 *       code, rather than a hard-coded Canadian dollar code for anything containing a
 *       {@code $} (inventory D3).</li>
 * </ul>
 */
@Component
public class ParsingStage implements PipelineStage {

    private static final Logger LOG = LoggerFactory.getLogger(ParsingStage.class);

    private final HeuristicExtractor extractor;
    private final ObjectMapper objectMapper;

    public ParsingStage(HeuristicExtractor extractor, ObjectMapper objectMapper) {
        this.extractor = extractor;
        this.objectMapper = objectMapper;
    }

    @Override
    public String stageName() {
        return "PARSING";
    }

    @Override
    public void process(Receipt receipt, ReceiptProcessingContext context)
        throws PipelineStageException {

        HeuristicExtractionResult result =
            extractor.extract(receipt.getOcrText(), context.country(), context.currency());

        if (receipt.getExtractedMerchant() == null && result.merchant() != null) {
            receipt.setExtractedMerchant(result.merchant());
        }
        if (receipt.getExtractedAmount() == null && result.amount() != null) {
            receipt.setExtractedAmount(result.amount());
        }
        if (receipt.getExtractedDate() == null && result.date() != null) {
            receipt.setExtractedDate(result.date());
        }
        // The currency is the one field this stage resolves rather than merely fills: the vision
        // stage only sets it when the RECEIPT printed an ISO code, and everything else — including
        // a bare '$' — is the member's own currency, decided here (D3).
        if (receipt.getExtractedCurrency() == null && result.currency() != null) {
            receipt.setExtractedCurrency(result.currency());
        }
        if (receipt.getExtractedLineItems() == null && !result.lineItems().isEmpty()) {
            receipt.setExtractedLineItems(toJson(result.lineItems(), receipt));
        }

        // The higher of the two: the model's own stated confidence, or what the regexes found.
        BigDecimal heuristic = result.confidence();
        if (heuristic.compareTo(context.getExtractionConfidence()) > 0) {
            context.setExtractionConfidence(heuristic);
        }
    }

    private String toJson(List<LineItemCandidate> items, Receipt receipt) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JacksonException e) {
            // Not fatal and not even worth failing the stage: the line items are a nicety and the
            // receipt's own fields are unaffected. The receipt id is the only thing logged.
            LOG.info("receipt-parsing: could not serialise line items for receipt {}",
                receipt.getId());
            return null;
        }
    }
}
