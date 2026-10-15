package finance.trove.receipts.receipt;

import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.storage.LocalDiskStorage;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a receipt's stored bytes back off the data volume (§4.10; gap row 11).
 *
 * <p><strong>Nothing is presigned and no storage URL is ever handed out.</strong> That was RS-7 in
 * the report service — presigned URLs signed against internal Docker DNS, unreachable from any
 * browser, with no public-endpoint override — and it is the same mistake a "just give the client a
 * link to the image" design would make here, with the added property that the link would work
 * without a session. The bytes come off this server's own volume through
 * {@link LocalDiskStorage}, which re-checks the stored key against its root on every read.
 *
 * <p>The lookup is household-scoped and a missing file is a 404, not a 500: the row is the index
 * and the file is the content, and a member whose file has gone needs the same answer as one
 * asking about a receipt that never existed.
 */
@Service
public class ReceiptFileService {

    private final ReceiptRepository receipts;
    private final LocalDiskStorage storage;

    public ReceiptFileService(ReceiptRepository receipts, LocalDiskStorage storage) {
        this.receipts = receipts;
        this.storage = storage;
    }

    /** The full-size stored image. Always a JPEG: the upload re-encodes everything it accepts. */
    @Transactional(readOnly = true)
    public StoredFile download(UUID householdId, UUID receiptId) {
        return read(householdId, receiptId, ReceiptImageKind.ORIGINAL);
    }

    /** The thumbnail. */
    @Transactional(readOnly = true)
    public StoredFile thumbnail(UUID householdId, UUID receiptId) {
        return read(householdId, receiptId, ReceiptImageKind.THUMBNAIL);
    }

    private StoredFile read(UUID householdId, UUID receiptId, ReceiptImageKind kind) {
        Receipt receipt = receipts.findByIdAndHouseholdId(receiptId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("No such receipt"));
        ReceiptImage image = receipt.getImages().stream()
            .filter(candidate -> candidate.getKind() == kind)
            .findFirst()
            .orElseThrow(() -> new ResourceNotFoundException("No such receipt image"));
        if (!storage.exists(image.getFileKey())) {
            throw new ResourceNotFoundException("No such receipt image");
        }
        return new StoredFile(storage.readAllBytes(image.getFileKey()), image.getMimeType(),
            receiptId + ".jpg");
    }

    /**
     * Bytes plus the two things the response needs to describe them.
     *
     * <p>{@code filename} is built from the receipt's own id, never from anything a client sent:
     * a {@code Content-Disposition} filename derived from an upload is how a header injection and
     * a {@code receipt.jpg.php} both get expressed.
     */
    public record StoredFile(byte[] content, String contentType, String filename) {
    }
}
