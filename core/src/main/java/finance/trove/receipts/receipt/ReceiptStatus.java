package finance.trove.receipts.receipt;

/**
 * Where a receipt is in its pipeline ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6, §3 V37-row).
 *
 * <p>Two values of the pre-collapse enum are gone. {@code QUEUED} was reserved "for future use"
 * and assigned by no code path that ever existed (inventory receipt-processor D7). {@code OCR}
 * left with PaddleOCR, whose wire protocol was invented rather than implemented (D8) — the vision
 * model reads the image directly now, so there is no separate text-extraction stage to be in.
 *
 * <p><strong>Every name here that a stage carries must match that stage's
 * {@code PipelineStage#stageName()} exactly.</strong> {@link finance.trove.receipts.pipeline
 * .ReceiptPipelineOrchestrator} sets the status with {@code ReceiptStatus.valueOf(stage
 * .stageName())} before running each stage, and the column carries a CHECK constraint listing
 * these nine strings; a stage whose name is not one of them fails every receipt it touches on the
 * INSERT rather than at compile time. That is why the vision stage is called {@code EXTRACTING}
 * and not, say, {@code VISION} (§2.6).
 */
public enum ReceiptStatus {

    /** Stored, not yet processed. The terminal state too when nothing will process it. */
    UPLOADED,

    /** Re-reading the stored bytes and confirming they are still the image that was accepted. */
    VALIDATING,

    /** Heuristic parsing of whatever text is available. */
    PARSING,

    /** The vision model is reading the image. Named for the enum value, not for the model. */
    EXTRACTING,

    /** Looking for a transaction in the household's ledger that this receipt could belong to. */
    MATCHING,

    /** Reverse-calculating GST/PST/HST. Canadian members only; a no-op for everyone else. */
    TAX_SPLIT,

    /** Composite confidence, and the decision whether a human should look. */
    SCORING,

    /** Terminal. The pipeline finished; {@code reviewRequired} says whether it needs a human. */
    COMPLETE,

    /** Terminal. The pipeline could not process the file; the stored image is still there. */
    FAILED;

    /** Whether no further processing will happen — what the orphan sweep and clients both ask. */
    public boolean isTerminal() {
        return this == COMPLETE || this == FAILED;
    }
}
