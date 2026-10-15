package finance.trove.receipts.receipt;

import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <strong>The one and only writer of either half of the receipt/transaction link</strong>
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3's foreign-key list; V38's header comment).
 *
 * <p>The link has two halves — {@code receipt.matched_transaction_id} and
 * {@code transaction.receipt_id} — because each answers a different question without a join: which
 * transaction a receipt is attached to, from the receipt screen, and whether a transaction has a
 * receipt, from the ledger row and from step 15's tax export. Two columns that can disagree is the
 * failure this class exists to prevent, and the guarantee is not "two writers that agree" but
 * <strong>one writer</strong>:
 *
 * <ul>
 *   <li>{@link Receipt#setMatchedTransactionId} is package-private and this class is in that
 *       package;</li>
 *   <li>{@link Transaction#setReceiptId} is written from here and from nowhere else in the tree;</li>
 *   <li>both calls happen inside one {@code @Transactional} method, together with the clearing of
 *       any previous partner on either side, so there is no window in which one half is written
 *       and the other is not.</li>
 * </ul>
 *
 * <p>Both callers — the pipeline's matching stage and the member's own
 * {@code PATCH /v1/receipts/{id}} — come through here. Everything is resolved inside the
 * household: a transaction id from another household is a 404, never a 403, and never a link.
 */
@Service
public class ReceiptLinkService {

    private final ReceiptRepository receipts;
    private final TransactionQueryService transactions;

    public ReceiptLinkService(ReceiptRepository receipts, TransactionQueryService transactions) {
        this.receipts = receipts;
        this.transactions = transactions;
    }

    /**
     * Attaches a receipt to a transaction, writing both halves.
     *
     * <p>Moving a link is handled here too: if the receipt was already attached to something else,
     * that transaction's {@code receiptId} is cleared, and if the target transaction already
     * carried a different receipt, that receipt's {@code matchedTransactionId} is cleared. Either
     * omission would leave a half-link pointing at a partner that no longer points back.
     *
     * @param householdId   the caller's household — the scope of every lookup below
     * @param receiptId     the receipt to attach
     * @param transactionId the transaction to attach it to
     * @throws ResourceNotFoundException when either id is not this household's
     */
    @Transactional
    public void link(UUID householdId, UUID receiptId, UUID transactionId) {
        Receipt receipt = receipts.findByIdAndHouseholdId(receiptId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("No such receipt"));
        link(receipt, transactionId);
    }

    /**
     * The same link, against a {@link Receipt} the caller already holds.
     *
     * <p><strong>This overload exists because the other one is unsafe for a caller that is holding
     * its own copy of the row.</strong> The pipeline's matching stage runs against the receipt the
     * orchestrator loaded, and the orchestrator saves that instance again when the stage returns.
     * If the link had been written to a second copy loaded in here, that save would merge the
     * caller's stale instance straight back over it and the link would silently vanish — which is
     * exactly the half-written link the whole design is shaped to prevent, arriving by a different
     * door. Writing to the caller's own instance means the two saves agree.
     *
     * @param receipt       the caller's live entity, already resolved inside its household
     * @param transactionId a transaction of the same household
     * @throws ResourceNotFoundException when the transaction is not this household's
     */
    @Transactional
    public void link(Receipt receipt, UUID transactionId) {
        UUID householdId = receipt.getHouseholdId();
        // Locked, not merely read: this row is about to be written, and the lock is what stops a
        // second caller writing it between this read and that write. See the class javadoc.
        Transaction target = transactions.findOneForUpdate(householdId, transactionId)
            .orElseThrow(() -> new ResourceNotFoundException("No such transaction"));

        if (transactionId.equals(receipt.getMatchedTransactionId())) {
            return;
        }

        clearPreviousPartnerOf(householdId, receipt);
        clearPreviousReceiptOn(householdId, target);

        receipt.setMatchedTransactionId(transactionId);
        target.setReceiptId(receipt.getId());
        receipts.save(receipt);
        transactions.saveLinked(target);
    }

    /**
     * Detaches a receipt from whatever it was attached to, writing both halves.
     *
     * <p>Also what the delete route calls before removing a receipt: the database's
     * {@code ON DELETE SET NULL} would clear the transaction's column anyway, but doing it here
     * keeps "who writes this column" a fact about the code rather than a fact split between the
     * code and a constraint.
     */
    @Transactional
    public void unlink(UUID householdId, UUID receiptId) {
        receipts.findByIdAndHouseholdId(receiptId, householdId).ifPresent(receipt -> {
            clearPreviousPartnerOf(householdId, receipt);
            receipt.setMatchedTransactionId(null);
            receipts.save(receipt);
        });
    }

    private void clearPreviousPartnerOf(UUID householdId, Receipt receipt) {
        UUID previous = receipt.getMatchedTransactionId();
        if (previous == null) {
            return;
        }
        transactions.findOneForUpdate(householdId, previous).ifPresent(old -> {
            if (receipt.getId().equals(old.getReceiptId())) {
                old.setReceiptId(null);
                transactions.saveLinked(old);
            }
        });
    }

    private void clearPreviousReceiptOn(UUID householdId, Transaction target) {
        UUID previous = target.getReceiptId();
        if (previous == null) {
            return;
        }
        Optional<Receipt> old = receipts.findByIdAndHouseholdId(previous, householdId);
        old.ifPresent(other -> {
            other.setMatchedTransactionId(null);
            receipts.save(other);
        });
    }
}
