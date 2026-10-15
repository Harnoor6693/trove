package finance.trove.receipts.receipt;

import finance.trove.common.domain.PagedResult;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.receipts.receipt.dto.ReceiptPatchRequest;
import finance.trove.receipts.receipt.dto.ReceiptSummaryResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading, editing and deleting the household's receipts (§4.10, §6 "Receipts").
 *
 * <p><strong>Everything is scoped by {@code household_id} and answers 404, never 403.</strong>
 * A receipt is shared ledger data: a household mate can read, edit, download and delete one
 * somebody else photographed, because that is what one shared household ledger means (§1.5) — and
 * this is the ordinary rule, not the exception collapse steps 11, 13 and 15 make for their own
 * per-member tables. Household B cannot reach it at all, and gets the same "no such receipt"
 * answer as for an id that never existed, so neither confirms the other.
 */
@Service
public class ReceiptService {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptService.class);

    private final ReceiptRepository receipts;
    private final ReceiptLinkService links;
    private final LocalDiskStorage storage;

    public ReceiptService(ReceiptRepository receipts, ReceiptLinkService links,
                          LocalDiskStorage storage) {
        this.receipts = receipts;
        this.links = links;
        this.storage = storage;
    }

    /** One receipt of this household, or 404. */
    @Transactional(readOnly = true)
    public Receipt get(UUID householdId, UUID receiptId) {
        return receipts.findByIdAndHouseholdId(receiptId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("No such receipt"));
    }

    /** One page of the household's receipts, newest first. */
    @Transactional(readOnly = true)
    public PagedResult<ReceiptSummaryResponse> list(UUID householdId, int page, int limit) {
        List<ReceiptSummaryResponse> rows =
            receipts.findSummaries(householdId, PageRequest.of(page, limit));
        return new PagedResult<>(rows, receipts.countByHouseholdId(householdId), page, limit);
    }

    /**
     * Writes the manual fields, and the link if one was asked for.
     *
     * <p>Refuses a receipt whose pipeline is still running with 409 {@code conflict}: the next
     * stage would overwrite whatever was just written, so accepting the request would be accepting
     * an edit that silently disappears.
     *
     * <p>The link goes through {@link ReceiptLinkService} — the one writer of either half — rather
     * than being set here, which is what keeps {@code receipt.matched_transaction_id} and
     * {@code transaction.receipt_id} from disagreeing.
     */
    @Transactional
    public Receipt patch(UUID householdId, UUID receiptId, ReceiptPatchRequest request) {
        if (request.isEmpty()) {
            throw new ValidationException("The request changes nothing; name at least one field");
        }
        if (request.hasBlankMerchant()) {
            throw new ValidationException("extractedMerchant must not be blank");
        }

        Receipt receipt = get(householdId, receiptId);
        requireTerminal(receipt);

        if (request.extractedMerchant() != null) {
            receipt.setExtractedMerchant(request.extractedMerchant().strip());
        }
        if (request.extractedAmount() != null) {
            receipt.setExtractedAmount(request.extractedAmount());
        }
        if (request.extractedCurrency() != null) {
            receipt.setExtractedCurrency(request.extractedCurrency());
        }
        if (request.extractedDate() != null) {
            receipt.setExtractedDate(request.extractedDate());
        }
        Receipt saved = receipts.save(receipt);

        if (request.matchedTransactionId() != null) {
            links.link(householdId, receiptId, request.matchedTransactionId());
            return get(householdId, receiptId);
        }
        return saved;
    }

    /**
     * Records the reviewer's verdict.
     *
     * <p>{@code false} is a member saying the extraction is not worth correcting, which is a
     * {@code FAILED} row with the image still stored and still downloadable — the file is the
     * evidence and the extraction was only ever a convenience.
     */
    @Transactional
    public Receipt review(UUID householdId, UUID receiptId, boolean accepted) {
        Receipt receipt = get(householdId, receiptId);
        requireTerminal(receipt);

        if (accepted) {
            receipt.setReviewRequired(false);
            receipt.setErrorMessage(null);
            receipt.setStatus(ReceiptStatus.COMPLETE);
        } else {
            receipt.setStatus(ReceiptStatus.FAILED);
            receipt.setReviewRequired(false);
            receipt.setErrorMessage("Rejected on review");
        }
        receipt.setProcessingCompletedAt(Instant.now());
        return receipts.save(receipt);
    }

    /**
     * Removes the receipt, its image rows, its line items <strong>and the files behind them</strong>.
     *
     * <p>A row whose JPEG is still on the volume is the beginning of gap row 30's unbounded disk
     * growth, so the two go together. The file deletes are registered for after the commit: a file
     * removed inside a transaction that then rolls back is gone for good, while a row committed
     * without its file being removed is an orphan the hourly sweep collects. Losing the member's
     * image is the worse of the two, so the order is "commit, then delete".
     *
     * <p>The link is cleared through {@link ReceiptLinkService} first. The database's
     * {@code ON DELETE SET NULL} would do it anyway; doing it here keeps "who writes
     * {@code transaction.receipt_id}" a fact about the code rather than one split between the code
     * and a constraint.
     */
    @Transactional
    public void delete(UUID householdId, UUID receiptId) {
        Receipt receipt = get(householdId, receiptId);
        List<String> keys = receipt.getImages().stream().map(ReceiptImage::getFileKey).toList();

        links.unlink(householdId, receiptId);
        long removed = receipts.deleteByIdAndHouseholdId(receiptId, householdId);
        if (removed == 0) {
            throw new ResourceNotFoundException("No such receipt");
        }
        deleteFilesAfterCommit(keys, receiptId);
    }

    private void deleteFilesAfterCommit(List<String> keys, UUID receiptId) {
        Runnable work = () -> {
            for (String key : keys) {
                if (!storage.delete(key)) {
                    // Already gone, or never written. The sweep would collect it in any case;
                    // this is worth a line and not worth an exception, because the row is gone.
                    LOG.info("receipt-delete: stored file of receipt {} was already absent",
                        receiptId);
                }
            }
        };
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()) {
            work.run();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager
            .registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        work.run();
                    }
                });
    }

    /**
     * Refuses to edit a receipt the pipeline is still walking through.
     *
     * <p>409 rather than 400 because nothing about the request is wrong — it is the state that
     * does not allow it, and the same request a minute later succeeds.
     */
    private static void requireTerminal(Receipt receipt) {
        if (!receipt.getStatus().isTerminal()) {
            throw new ConflictException(
                "This receipt is still being processed; wait until it is complete");
        }
    }
}
