package finance.trove.receipts.matching;

import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptLinkService;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Finds the transaction a receipt belongs to, in process
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6, §6 "Receipts").
 *
 * <p>The source's {@code TransactionMatchingService} was an HTTP client against another service,
 * authenticated with an {@code X-User-Id} header the caller chose — the G1 identity defect, and
 * a string {@code SourceConventionTest} now bans outright. Its matching <em>logic</em> is what
 * survives: the same &plusmn;$0.50 and &plusmn;1-day window, the same "one candidate is certain,
 * several is the closest of them" scoring. What changed is where the candidates come from:
 * {@link TransactionQueryService#findCandidates}, scoped by {@code household_id}, so a receipt can
 * only match its own household's ledger.
 *
 * <p><strong>The window is stated here and nowhere else.</strong> &plusmn;$0.50 covers a tip
 * written on the slip after the card was swiped and the rounding some terminals apply; &plusmn;1
 * day covers a purchase made near midnight in one timezone and posted in another. Widening either
 * makes the wrong match likelier, and a wrong match is worse than no match: no match asks the
 * member, a wrong one quietly attaches a receipt to somebody else's coffee.
 *
 * <p><strong>It does not write the link.</strong> {@link ReceiptLinkService} does, because both
 * halves — {@code receipt.matched_transaction_id} and {@code transaction.receipt_id} — must be
 * written in one transaction and there must be exactly one place that does it.
 */
@Service
public class ReceiptMatcher {

    /** The amount window. A tip added after the swipe, or a terminal's rounding. */
    public static final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.50");

    /** The date window. A late-night purchase posted the next day. */
    public static final int DATE_TOLERANCE_DAYS = 1;

    /** One candidate in the window and nothing else: as certain as this gets. */
    static final BigDecimal SINGLE_MATCH_CONFIDENCE = new BigDecimal("1.000");

    /** The closest of several. Lower, because "closest" is not "correct". */
    static final BigDecimal BEST_OF_SEVERAL_CONFIDENCE = new BigDecimal("0.800");

    private final TransactionQueryService transactions;
    private final ReceiptLinkService links;

    public ReceiptMatcher(TransactionQueryService transactions, ReceiptLinkService links) {
        this.transactions = transactions;
        this.links = links;
    }

    /**
     * Looks for a match and links it if there is one.
     *
     * <p>{@code matchAttempted} on the context is set only when there was something to look for
     * <em>and</em> the household's ledger had at least one candidate in the window. That
     * distinction is the other half of the D1 fix: the scoring stage renormalises its weights when
     * no candidate existed, because a match weight over a search that never happened is a ceiling
     * no receipt can clear.
     */
    public void match(Receipt receipt, ReceiptProcessingContext context) {
        context.setMatchAttempted(false);
        context.setMatchConfidence(BigDecimal.ZERO);

        if (receipt.getExtractedAmount() == null || receipt.getExtractedDate() == null) {
            // Nothing to search on. Not a failure — the member can link it by hand.
            return;
        }

        List<Transaction> candidates = transactions.findCandidates(receipt.getHouseholdId(),
            receipt.getExtractedAmount(), AMOUNT_TOLERANCE, receipt.getExtractedDate(),
            DATE_TOLERANCE_DAYS);
        if (candidates.isEmpty()) {
            return;
        }

        context.setMatchAttempted(true);
        if (candidates.size() == 1) {
            context.setMatchConfidence(SINGLE_MATCH_CONFIDENCE);
            // The entity overload, deliberately: the orchestrator saves this very
            // instance when the stage returns, and linking through a second copy would
            // let that save merge a stale null back over the link.
            links.link(receipt, candidates.get(0).getId());
            return;
        }

        Transaction best = candidates.stream()
            .min(Comparator.comparing(candidate ->
                receipt.getExtractedAmount().subtract(candidate.getAmount()).abs()))
            .orElseThrow();
        context.setMatchConfidence(BEST_OF_SEVERAL_CONFIDENCE);
        links.link(receipt, best.getId());
    }
}
