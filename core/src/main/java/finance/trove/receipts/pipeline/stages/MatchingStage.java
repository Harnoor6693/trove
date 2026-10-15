package finance.trove.receipts.pipeline.stages;

import finance.trove.receipts.matching.ReceiptMatcher;
import finance.trove.receipts.pipeline.PipelineStage;
import finance.trove.receipts.pipeline.PipelineStageException;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.Receipt;
import org.springframework.stereotype.Component;

/**
 * Stage 4 — looks for a transaction in the household's ledger this receipt could belong to
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6).
 *
 * <p><strong>The HTTP hop is gone.</strong> The source's {@code TransactionMatchingService} was a
 * {@code RestClient} against the ingestion service, authenticating with an
 * {@code X-User-Id} header — a client-supplied identity, which is the G1 defect the whole collapse
 * exists to remove, and which {@code SourceConventionTest} now bans as a string. The lookup is an
 * in-process call to {@link finance.trove.transactions.TransactionQueryService} scoped by
 * {@code household_id}, so a receipt can only ever match its own household's ledger.
 *
 * <p>The stage itself does almost nothing: {@link ReceiptMatcher} holds the window, the scoring and
 * the tie-break, and {@link finance.trove.receipts.receipt.ReceiptLinkService} does the writing —
 * because writing one half of the receipt/transaction link from a pipeline stage is exactly the
 * inconsistency V38's comment exists to prevent.
 */
@Component
public class MatchingStage implements PipelineStage {

    private final ReceiptMatcher matcher;

    public MatchingStage(ReceiptMatcher matcher) {
        this.matcher = matcher;
    }

    @Override
    public String stageName() {
        return "MATCHING";
    }

    @Override
    public void process(Receipt receipt, ReceiptProcessingContext context)
        throws PipelineStageException {
        matcher.match(receipt, context);
    }
}
