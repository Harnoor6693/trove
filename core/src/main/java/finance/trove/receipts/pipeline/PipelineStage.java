package finance.trove.receipts.pipeline;

import finance.trove.receipts.receipt.Receipt;

/**
 * One step of the receipt pipeline ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6 — KEPT from the
 * source, whose inventory calls this package "core business logic, well-isolated, well-tested").
 *
 * <p><strong>{@link #stageName()} must be a {@code ReceiptStatus} value, spelled exactly.</strong>
 * {@link ReceiptPipelineOrchestrator} sets the receipt's status with
 * {@code ReceiptStatus.valueOf(stage.stageName())} before running the stage, and the column carries
 * a CHECK constraint over those nine strings — so a stage whose name is not one of them fails every
 * receipt it touches on the write rather than at compile time. It is why the vision stage is named
 * {@code EXTRACTING} (§2.6).
 */
public interface PipelineStage {

    /** The {@code ReceiptStatus} value this stage runs under, spelled exactly. */
    String stageName();

    /**
     * Does this stage's work, mutating the receipt and the context in place.
     *
     * @throws PipelineStageException fatal to stop the run and fail the receipt, non-fatal to log
     *                                and continue with what has been written so far
     */
    void process(Receipt receipt, ReceiptProcessingContext context) throws PipelineStageException;
}
