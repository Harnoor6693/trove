package finance.trove.receipts.pipeline;

import finance.trove.ai.AiGateway;
import finance.trove.app.AsyncConfig;
import finance.trove.common.events.ReceiptProcessedEvent;
import finance.trove.common.time.Instants;
import finance.trove.receipts.ReceiptEventPublisher;
import finance.trove.receipts.pipeline.stages.MatchingStage;
import finance.trove.receipts.pipeline.stages.ParsingStage;
import finance.trove.receipts.pipeline.stages.ScoringStage;
import finance.trove.receipts.pipeline.stages.TaxSplitStage;
import finance.trove.receipts.pipeline.stages.ValidationStage;
import finance.trove.receipts.pipeline.stages.VisionExtractionStage;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptRepository;
import finance.trove.receipts.receipt.ReceiptStatus;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Drives the six stages ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6, §5 items 7 and 9).
 *
 * <p>The source's orchestrator is kept in shape and changed in five ways:
 *
 * <ol>
 *   <li><strong>Seven stages become six.</strong> {@code OcrStage} is deleted with PaddleOCR, whose
 *       wire protocol was invented rather than implemented (inventory D8). The order is
 *       VALIDATING &rarr; EXTRACTING &rarr; PARSING &rarr; MATCHING &rarr; TAX_SPLIT &rarr;
 *       SCORING: the vision model produces the text now, so extraction runs <em>before</em>
 *       parsing rather than as a fallback after it.</li>
 *   <li><strong>The receipt is re-read before every stage, and the run stops if it is gone.</strong>
 *       The source held one detached entity for the whole run and saved it after each stage; if a
 *       member deleted the receipt mid-pipeline, that save would <em>re-insert</em> the row that
 *       had just been deleted and orphan its files with it. Re-reading is also what makes deleting
 *       a receipt while it is being processed safe to allow at all.</li>
 *   <li><strong>The read is household-scoped like every other read in the tree.</strong>
 *       {@link #processAsync} takes the household id along with the receipt id and looks the row up
 *       with {@code findByIdAndHouseholdId} — there is no unscoped finder on
 *       {@link ReceiptRepository} to reach for, and a background thread is exactly where one would
 *       go unnoticed.</li>
 *   <li><strong>{@code catch (Exception)} is gone.</strong> The source caught {@code Exception}
 *       around every stage, which is how a defect hides for a year. A stage signals with
 *       {@link PipelineStageException} and says whether it is fatal; a {@link RuntimeException}
 *       escaping one is a bug, and it fails the receipt with a generic message and a full stack
 *       trace in the log rather than being flattened into a warning.</li>
 *   <li><strong>{@code trove.receipt.stage} is recorded</strong> — the {@link Timer} tagged
 *       {@code stage} that §5 item 9 names and that did not exist.</li>
 * </ol>
 *
 * <h2>No transaction spans a stage, deliberately</h2>
 * Each repository call takes its own. Two reasons: the source's own contract is that a polling
 * client sees the status move, which needs each stage's write to be visible as it happens; and the
 * extraction stage makes a network call whose timeout is measured in minutes, so a transaction
 * around it would pin a database connection for the length of somebody else's model inference.
 * Both of {@link Receipt}'s collections are {@code EAGER} for the same reason — there is no open
 * session between stages for a lazy one to initialise against.
 *
 * <h2>Whose settings</h2>
 * The context is built from the <em>uploader's</em> row — their country, region and currency — not
 * from whoever is looking at a screen, because there is no request thread here at all. That is the
 * same rule {@link AiGateway#forUser} follows for the vision call inside the extraction stage.
 *
 * <p>The terminal event is published through {@link ReceiptEventPublisher} after the final write.
 * {@code NotificationEventListener} (inbox) and {@code WebhookEventListener} (webhook delivery)
 * consume it.
 */
@Service
public class ReceiptPipelineOrchestrator {

    /** §5 item 9's meter: a {@link Timer} per stage, tagged {@code stage}. */
    public static final String STAGE_TIMER = "trove.receipt.stage";

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptPipelineOrchestrator.class);

    private final ReceiptRepository receipts;
    private final UserRepository users;
    private final ReceiptEventPublisher publisher;
    private final MeterRegistry meters;
    private final List<PipelineStage> stages;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReceiptPipelineOrchestrator(ReceiptRepository receipts, UserRepository users,
                                       ReceiptEventPublisher publisher, MeterRegistry meters,
                                       ValidationStage validation, VisionExtractionStage vision,
                                       ParsingStage parsing, MatchingStage matching,
                                       TaxSplitStage taxSplit, ScoringStage scoring) {
        this.receipts = receipts;
        this.users = users;
        this.publisher = publisher;
        this.meters = meters;
        // Assembled here rather than injected as a List<PipelineStage>, because the ORDER is the
        // contract and bean-collection order is not something a reader of this class could see.
        this.stages = List.of(validation, vision, parsing, matching, taxSplit, scoring);
    }

    /**
     * Runs the pipeline on {@code receiptExecutor}.
     *
     * <p>{@code AbortPolicy} means a full queue throws {@code TaskRejectedException} at the caller;
     * {@code ReceiptUploadService} catches it, leaves the row {@code UPLOADED} and still answers
     * 202. Nothing here needs to know that.
     *
     * @param householdId the receipt's household — the scope of every read below
     * @param receiptId   the row to process
     */
    @Async(AsyncConfig.RECEIPT_EXECUTOR)
    public void processAsync(UUID householdId, UUID receiptId) {
        run(householdId, receiptId);
    }

    /**
     * One synchronous pass, so a test and the orphan sweep can drive it without a thread pool.
     *
     * <p>A receipt that is already gone is a no-op, not an error.
     */
    public void run(UUID householdId, UUID receiptId) {
        Optional<Receipt> found = load(householdId, receiptId);
        if (found.isEmpty()) {
            LOG.info("receipt-pipeline: receipt {} no longer exists; nothing to process",
                receiptId);
            return;
        }
        UUID uploaderId = found.get().getUserId();
        ReceiptProcessingContext context = contextFor(uploaderId);

        Receipt started = found.get();
        started.setProcessingStartedAt(Instants.now());
        receipts.save(started);

        for (PipelineStage stage : stages) {
            // Re-read every time: the member may have deleted the receipt while the last stage
            // ran, and saving a detached copy of a deleted row would re-insert it.
            Optional<Receipt> current = load(householdId, receiptId);
            if (current.isEmpty()) {
                LOG.info("receipt-pipeline: receipt {} was deleted mid-run; stopping before "
                    + "stage {}", receiptId, stage.stageName());
                return;
            }
            if (!runStage(stage, current.get(), context)) {
                publishTerminal(householdId, uploaderId, receiptId);
                return;
            }
        }

        load(householdId, receiptId).ifPresent(finished -> {
            finished.setStatus(ReceiptStatus.COMPLETE);
            finished.setProcessingCompletedAt(Instants.now());
            receipts.save(finished);
        });
        publishTerminal(householdId, uploaderId, receiptId);
    }

    /**
     * Runs one stage and answers whether the pipeline should continue.
     *
     * @return true to carry on, false when the receipt has been failed and the run must stop
     */
    private boolean runStage(PipelineStage stage, Receipt receipt,
                             ReceiptProcessingContext context) {
        Timer.Sample sample = Timer.start(meters);
        try {
            // The status is the stage's own name; ReceiptStatus.valueOf is what makes the CHECK
            // constraint and the enum agree, and what forces the vision stage to be EXTRACTING.
            receipt.setStatus(ReceiptStatus.valueOf(stage.stageName()));
            receipts.save(receipt);

            stage.process(receipt, context);
            receipts.save(receipt);
            return true;
        } catch (PipelineStageException e) {
            if (!e.isFatal()) {
                // Partial results a stage already wrote are kept deliberately: a receipt whose
                // merchant was read but whose date was not is worth more than an empty one.
                receipts.save(receipt);
                LOG.info("receipt-pipeline: stage {} on receipt {} did not complete: {}",
                    e.getStageName(), receipt.getId(), e.getUserMessage());
                return true;
            }
            LOG.warn("receipt-pipeline: stage {} failed receipt {}: {}", e.getStageName(),
                receipt.getId(), e.getUserMessage());
            fail(receipt, e.getUserMessage());
            return false;
        } catch (RuntimeException e) {
            // NOT a catch(Exception) around the loop, which is how the source hid its defects:
            // this is the "should not happen" branch and it keeps its stack trace.
            LOG.error("receipt-pipeline: unexpected failure in stage {} on receipt {}",
                stage.stageName(), receipt.getId(), e);
            fail(receipt, "The receipt could not be processed");
            return false;
        } finally {
            sample.stop(Timer.builder(STAGE_TIMER)
                .description("How long one receipt pipeline stage took")
                .tag("stage", stage.stageName())
                .register(meters));
        }
    }

    private Optional<Receipt> load(UUID householdId, UUID receiptId) {
        return receipts.findByIdAndHouseholdId(receiptId, householdId);
    }

    /**
     * Puts a receipt into {@code FAILED}, and does so even when the entity itself is what the
     * database refused.
     *
     * <p><strong>The second write is not belt and braces, it is the whole point.</strong> This
     * method is reached from the {@code catch (RuntimeException)} above, and one of the things
     * that runtime exception can be is a {@code DataIntegrityViolationException} from the
     * {@code save} inside {@link #runStage} — a value some stage put on the receipt that the
     * column will not hold. Saving the same entity again with that same value still on it throws
     * the same exception, out of {@code fail}, out of {@code runStage}, out of {@code run} and out
     * of the {@code @Async} method where nobody is waiting for it. The row stays in whatever
     * non-terminal status it last committed, {@link finance.trove.receipts.ReceiptOrphanSweep}
     * re-enqueues it every hour, and one of the sweep's bounded slots is spent on a receipt that
     * can never finish. So the fallback writes the four terminal columns with a targeted
     * {@code UPDATE} that carries none of the entity's other values: whatever is wrong with the
     * row, the row still reaches a terminal state and the sweep lets go of it.
     */
    private void fail(Receipt receipt, String message) {
        receipt.setStatus(ReceiptStatus.FAILED);
        receipt.setErrorMessage(message);
        receipt.setReviewRequired(true);
        receipt.setProcessingCompletedAt(Instants.now());
        try {
            receipts.save(receipt);
        } catch (RuntimeException e) {
            LOG.error("receipt-pipeline: receipt {} could not be saved while being failed; "
                + "writing the terminal status directly", receipt.getId(), e);
            receipts.markFailed(receipt.getId(), receipt.getHouseholdId(), message,
                Instants.now());
        }
    }

    /**
     * The uploader's country, region and currency, or a neutral context.
     *
     * <p>{@code uploaderId} is null for a receipt whose uploader has left the household: V43 makes
     * {@code receipt.user_id} nullable with ON DELETE SET NULL, because a receipt is a shared
     * household ledger row and the shared ledger outlives its author (owner decision 17). That is
     * a real state the orphan sweep re-runs into, so it degrades to exactly the context an unknown
     * uploader already produced — the stages read the three fields and each already handles them
     * being absent — rather than throwing a 500 out of a scheduled job.
     *
     * <p>An uploader whose account is inside the deletion grace still resolves and gives their
     * normal context. If the purge lands between reading the receipt and the lookup, the lookup
     * finds nobody, so the context carries no country, region or currency, which every stage that
     * needs one declines on rather than guessing.
     */
    private ReceiptProcessingContext contextFor(UUID uploaderId) {
        if (uploaderId == null) {
            return new ReceiptProcessingContext(null, null, null);
        }
        return users.findAccount(uploaderId)
            .map(this::contextOf)
            .orElseGet(() -> new ReceiptProcessingContext(null, null, null));
    }

    private ReceiptProcessingContext contextOf(User uploader) {
        return new ReceiptProcessingContext(uploader.getCountry(), uploader.getRegion(),
            uploader.getCurrency());
    }

    /**
     * Announces the terminal state, unless there is nobody it belongs to.
     *
     * <p>{@code ReceiptProcessedEvent} requires a non-null {@code userId} and both its consumers
     * are addressed to that member alone — the inbox row is the uploader's and the webhook
     * delivery goes to the uploader's subscriptions. A receipt whose uploader has been purged
     * (V43's null) therefore has no recipient at all: publishing would throw inside a scheduled
     * sweep, and inventing a recipient would put one member's photograph into another member's
     * integration. It is processed normally and announced to nobody.
     */
    private void publishTerminal(UUID householdId, UUID uploaderId, UUID receiptId) {
        if (uploaderId == null) {
            LOG.debug("receipt-pipeline: receipt {} has no uploader left; nothing to announce",
                receiptId);
            return;
        }
        load(householdId, receiptId).ifPresent(finished -> {
            ReceiptProcessedEvent.Outcome outcome;
            if (finished.getStatus() == ReceiptStatus.FAILED) {
                outcome = ReceiptProcessedEvent.Outcome.FAILED;
            } else if (finished.isReviewRequired()) {
                outcome = ReceiptProcessedEvent.Outcome.REVIEW_REQUIRED;
            } else {
                outcome = ReceiptProcessedEvent.Outcome.COMPLETE;
            }
            publisher.publishAfterCommit(householdId, uploaderId, receiptId, outcome,
                finished.getMatchedTransactionId());
        });
    }
}
