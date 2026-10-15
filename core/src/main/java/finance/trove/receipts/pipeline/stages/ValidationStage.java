package finance.trove.receipts.pipeline.stages;

import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageException;
import finance.trove.receipts.pipeline.PipelineStage;
import finance.trove.receipts.pipeline.PipelineStageException;
import finance.trove.receipts.pipeline.ReceiptProcessingContext;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptImage;
import finance.trove.receipts.upload.MultipartFileValidator;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Stage 1 — confirms the stored image is still there and still what it was accepted as
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6, KEPT with its storage call repointed).
 *
 * <p>It looks smaller than the source's version and does less on purpose. The source re-checked
 * the size and re-sniffed the magic bytes here because <em>nothing did so at the door</em>: the
 * upload wrote {@code file.getBytes()} to MinIO verbatim, so this was the first and only look at
 * the content. Now the whole gauntlet runs before a byte is stored, and what is on the disk is a
 * JPEG this server itself encoded from a decoded raster — so re-deriving the type from the bytes
 * would only be re-checking this server's own writer.
 *
 * <p>What is left is the check that is still real: <strong>the file must still be there and still
 * be readable.</strong> Between the upload and the run the volume can fill, a key can be swept, a
 * restore can miss a file. Failing here — fatally — is what stops five later stages from working
 * on nothing and calling the result a low-confidence receipt.
 */
@Component
public class ValidationStage implements PipelineStage {

    private final LocalDiskStorage storage;

    public ValidationStage(LocalDiskStorage storage) {
        this.storage = storage;
    }

    @Override
    public String stageName() {
        return "VALIDATING";
    }

    @Override
    public void process(Receipt receipt, ReceiptProcessingContext context)
        throws PipelineStageException {

        Optional<ReceiptImage> original = receipt.original();
        if (original.isEmpty()) {
            throw new PipelineStageException(stageName(),
                "The stored receipt image is missing", true);
        }
        ReceiptImage image = original.get();
        try {
            if (!storage.exists(image.getFileKey())) {
                throw new PipelineStageException(stageName(),
                    "The stored receipt image could not be found", true);
            }
            if (storage.size(image.getFileKey()) <= 0) {
                throw new PipelineStageException(stageName(),
                    "The stored receipt image is empty", true);
            }
        } catch (StorageException e) {
            // The message deliberately carries no key and no path: a member cannot address the
            // store and an error body that names a key tells an attacker about one.
            throw new PipelineStageException(stageName(),
                "The stored receipt image could not be read", true, e);
        }
        // Always JPEG: the upload re-encodes everything it accepts, whatever was sent.
        context.setDetectedMimeType(MultipartFileValidator.IMAGE_JPEG);
    }
}
