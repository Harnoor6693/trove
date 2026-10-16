package finance.trove.receipts.pipeline.stages;

import finance.trove.ai.AiException;
import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.AiSensitivity;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.net.OutboundUrlPolicyException;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageException;
import finance.trove.receipts.extraction.HeuristicExtractor;
import finance.trove.receipts.extraction.LineItemCandidate;
import finance.trove.receipts.extraction.ReceiptPromptsProperties;
import finance.trove.receipts.extraction.VisionAnswer;
import finance.trove.receipts.extraction.VisionAnswerParser;
import finance.trove.receipts.pipeline.PipelineStage;
import finance.trove.receipts.pipeline.PipelineStageException;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptImage;
import finance.trove.receipts.upload.MultipartFileValidator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Stage 2 — the vision call ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6;
 * {@code docs/recovery/07-READINESS-GAPS.md} row 44).
 *
 * <p><strong>It is named {@code EXTRACTING} because it has to be.</strong>
 * {@code ReceiptPipelineOrchestrator} sets the status with
 * {@code ReceiptStatus.valueOf(stage.stageName())} and the column carries a CHECK over the enum's
 * nine values, so a stage called anything else would fail every receipt it touched on the write
 * (§2.6 states this explicitly).
 *
 * <h2>The payload is always SENSITIVE, and the consequence is deliberate</h2>
 * A receipt image is the household's ledger with a photograph attached — {@link AiSensitivity}'s
 * own javadoc names it as its example. It is therefore always
 * {@link AiSensitivity#SENSITIVE}, never conditionally: a member on {@code PrivacyTier.CLOUD_OK}
 * is refused with {@code AiPolicyBlockedException} before any connection is opened, and that
 * refusal <strong>degrades to the manual path exactly like AI being switched off</strong>. It does
 * not 403 anything, it is not surfaced to the uploader as an error, and {@code ai-policy-blocked}
 * stays unrouted from this step. The member's own privacy setting held their receipt back, which
 * is what they asked it to do.
 *
 * <h2>Whose AI</h2>
 * {@link AiGateway#forUser} with the <em>uploader's</em> id — {@code ai_settings} is per user
 * (owner decision 9). The handle is fetched here rather than passed in because the settings are a
 * snapshot and a background run may start long after the upload did.
 *
 * <h2>Every failure becomes review, and none of them is rethrown</h2>
 * {@link AiException} is the common base of the six typed failures {@link AiHandle} guarantees, and
 * it is caught rather than each subclass in turn because they all mean one thing here: this receipt
 * gets no extraction. {@link OutboundUrlPolicyException} is caught alongside it — narrower than a
 * bare {@code catch (Exception)}, which is how the old service hid its defects — because
 * {@link AiHandle}'s javadoc says a URL that was legal when it was saved can stop being legal by
 * the time it is called, and a receipt must not fail on that. Step 17's
 * {@code DefaultAiCategorisationTier} is the merged precedent and does exactly the same two
 * catches.
 *
 * <h2>The model does not get to decide that nobody needs to look at this</h2>
 * Every other field taken from the answer is checked against something outside it — an amount
 * against a decimal and a ceiling, a currency against {@code CurrencyCode}, a date against the
 * uploader's own country. {@code confidence} was the exception: the model's own number went
 * straight into the score, and with no matching candidate — the ordinary case — the composite
 * <em>is</em> that number. An endpoint answering {@code "confidence": 1.0} therefore cleared
 * {@link Receipt#REVIEW_THRESHOLD} on its own say-so, and the receipt, its line items and whatever
 * transaction the matching stage attached entered the household's ledger with
 * {@code reviewRequired = false} and no human ever asked. The model's self-report is now capped at
 * {@link #MODEL_CONFIDENCE_CEILING}, which is deliberately below that threshold: the model can
 * still <em>lower</em> the score as far as it likes — a model that says it could not read the
 * receipt is believed — but clearing the bar takes corroboration the server measured itself, which
 * is the heuristic re-reading of the transcription in {@code ParsingStage} or a matched
 * transaction. Gap row 44's discipline is that the answer is a suggestion; this is the one field
 * where that had not been applied.
 *
 * <h2>What is logged</h2>
 * The error slug and the receipt id. Never the merchant, never the extracted text, never the
 * image, never the prompt, never the answer, never the endpoint or its response body — an
 * OpenAI-compatible server routinely echoes the request back inside an error.
 */
@Component
public class VisionExtractionStage implements PipelineStage {

    /**
     * The highest score the model's own {@code confidence} may contribute by itself.
     *
     * <p>Below {@link Receipt#REVIEW_THRESHOLD} (0.920) on purpose and by a margin: see the class
     * javadoc. Raising this to or above the threshold hands the decision of whether a member
     * reviews their own receipt to whatever answered the vision call.
     */
    static final BigDecimal MODEL_CONFIDENCE_CEILING = new BigDecimal("0.850");

    private static final Logger LOG = LoggerFactory.getLogger(VisionExtractionStage.class);

    private final AiGateway gateway;
    private final LocalDiskStorage storage;
    private final VisionAnswerParser parser;
    private final ReceiptPromptsProperties prompts;
    private final ObjectMapper objectMapper;

    public VisionExtractionStage(AiGateway gateway, LocalDiskStorage storage,
                                 VisionAnswerParser parser, ReceiptPromptsProperties prompts,
                                 ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.storage = storage;
        this.parser = parser;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    @Override
    public String stageName() {
        // Must stay exactly this string: see the class javadoc and ReceiptStatus.
        return "EXTRACTING";
    }

    @Override
    public void process(Receipt receipt, ReceiptProcessingContext context)
        throws PipelineStageException {

        if (receipt.getUserId() == null) {
            // The uploader has left the household. V43 makes receipt.user_id nullable with
            // ON DELETE SET NULL because the receipt is a shared ledger row and the ledger
            // outlives its author (owner decision 17), so this is a real state the orphan sweep
            // re-runs into. ai_settings is per USER, so there is no member whose model, key or
            // egress policy could be used here — and borrowing another member's would spend their
            // budget on somebody else's photograph. It degrades exactly as an AI-off install
            // does: REVIEW_REQUIRED, no exception, no 500 out of a scheduled job.
            markForReview(receipt, context);
            return;
        }

        AiHandle handle = gateway.forUser(receipt.getUserId());
        if (!handle.isEnabled() || handle.visionModel() == null) {
            // The member switched AI off, or removed the vision model, between the upload and the
            // run. Ordinary, and the same outcome as never having had one. A member deleted since
            // the upload lands here too: the gateway reads an inactive member as AI-off (B3-14).
            markForReview(receipt, context);
            return;
        }

        Optional<ReceiptImage> original = receipt.original();
        if (original.isEmpty()) {
            throw new PipelineStageException(stageName(),
                "The stored receipt image is missing", true);
        }

        byte[] image;
        try {
            image = storage.readAllBytes(original.get().getFileKey());
        } catch (StorageException e) {
            throw new PipelineStageException(stageName(),
                "The stored receipt image could not be read", true, e);
        }

        AiCompletion completion = ask(handle, image, receipt);
        if (completion == null) {
            markForReview(receipt, context);
            return;
        }

        VisionAnswer answer = parser.parse(completion.content());
        if (answer.isEmpty()) {
            // Prose, malformed JSON, a missing field, a ten-kilobyte reply, a control character,
            // an out-of-range amount: every one of them lands here, with blank fields.
            markForReview(receipt, context);
            return;
        }
        apply(receipt, context, answer);
    }

    /** The one network call, with every failure it can produce turned into null. */
    private AiCompletion ask(AiHandle handle, byte[] image, Receipt receipt) {
        try {
            return handle.vision(prompts.extract(), image, MultipartFileValidator.IMAGE_JPEG,
                AiSensitivity.SENSITIVE);
        } catch (AiException e) {
            LOG.info("receipt-vision: {} for receipt {}; falling back to manual review",
                e.getSlug(), receipt.getId());
            return null;
        } catch (OutboundUrlPolicyException e) {
            LOG.info("receipt-vision: outbound-url-rejected for receipt {}; the saved endpoint no "
                + "longer passes the egress policy", receipt.getId());
            return null;
        }
    }

    private void apply(Receipt receipt, ReceiptProcessingContext context, VisionAnswer answer) {
        if (answer.text() != null) {
            receipt.setOcrText(answer.text());
        }
        if (answer.merchant() != null) {
            receipt.setExtractedMerchant(answer.merchant());
        }
        if (answer.amount() != null) {
            receipt.setExtractedAmount(answer.amount());
        }
        // An ISO code the RECEIPT printed wins; anything else is left for the parsing stage to
        // resolve from the member's own row (inventory D3). Never a literal.
        if (answer.currencyCode() != null) {
            for (CurrencyCode candidate : CurrencyCode.values()) {
                if (candidate.name().equals(answer.currencyCode())) {
                    receipt.setExtractedCurrency(candidate);
                }
            }
        }
        // The date is parsed HERE rather than being trusted as written, and with the UPLOADER's
        // country. The model was asked to copy the date exactly as printed precisely because it
        // does not know whose receipt this is, and 05/06/2026 is 5 June for a Canadian member and
        // 6 May for an American one (inventory D2). HeuristicExtractor.parseDate is the one
        // implementation of that rule; a second copy here is how the two would drift apart.
        LocalDate printed = HeuristicExtractor.parseDate(answer.rawDate(), context.country());
        if (printed != null) {
            receipt.setExtractedDate(printed);
        }
        // The lines the model read. They are only SUGGESTIONS until the scoring stage decides the
        // extraction was good enough to promote them to rows — but they have to be kept somewhere
        // until then, and without this the whole lineItems half of the answer was discarded and
        // only the regex parser's two-space-separated lines survived.
        if (!answer.lineItems().isEmpty()) {
            receipt.setExtractedLineItems(toJson(answer.lineItems(), receipt));
        }
        if (answer.confidence() != null) {
            // Capped, never taken as given: the model may lower its own score but may not clear
            // the review threshold with it. See the class javadoc.
            context.setExtractionConfidence(answer.confidence().min(MODEL_CONFIDENCE_CEILING));
        }
    }

    /** Serialises the candidates, losing them rather than the receipt if that cannot be done. */
    private String toJson(List<LineItemCandidate> candidates, Receipt receipt) {
        try {
            return objectMapper.writeValueAsString(candidates);
        } catch (JacksonException e) {
            LOG.info("receipt-vision: could not serialise the extracted line items of receipt {}",
                receipt.getId());
            return null;
        }
    }

    /**
     * No usable extraction: the fields stay blank and a human is asked.
     *
     * <p>Never a {@link PipelineStageException}, fatal or otherwise. The receipt is fine, the image
     * is stored, and the ordinary answer on an install without AI is exactly this.
     */
    private static void markForReview(Receipt receipt, ReceiptProcessingContext context) {
        receipt.setReviewRequired(true);
        context.setExtractionConfidence(java.math.BigDecimal.ZERO);
    }
}
