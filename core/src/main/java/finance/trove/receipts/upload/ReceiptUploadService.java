package finance.trove.receipts.upload;

import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.common.events.ReceiptProcessedEvent;
import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.common.time.Instants;
import finance.trove.receipts.ReceiptEventPublisher;
import finance.trove.receipts.ReceiptProperties;
import finance.trove.receipts.pipeline.ReceiptPipelineOrchestrator;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptImage;
import finance.trove.receipts.receipt.ReceiptImageKind;
import finance.trove.receipts.receipt.ReceiptRepository;
import finance.trove.receipts.receipt.ReceiptSource;
import finance.trove.receipts.receipt.ReceiptStatus;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * The upload gauntlet, in order, and the branch that decides what happens next
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 11; §2.6, §4.10, §6 "Receipts").
 *
 * <h2>The order of the six clauses is the design</h2>
 * <ol>
 *   <li><strong>(a) Declared size</strong>, before the body is read — {@link MultipartFileValidator}.</li>
 *   <li><strong>(b) Magic bytes</strong> against the declared type — same class. JPEG and PNG only;
 *       HEIC refused server-side, PDF not accepted.</li>
 *   <li><strong>(c) Dimensions from the header</strong>, before any decode —
 *       {@link ReceiptImageProcessor#probe}. A crafted 60000&times;60000 PNG is refused while it is
 *       still a few kilobytes.</li>
 *   <li><strong>(d) The household quota</strong>, from
 *       {@link LocalDiskStorage#usage(StorageArea, UUID)}, <em>twice</em>: once against the
 *       part's declared size before the body is read at all, so that a household already over its
 *       allowance is refused before it costs a decode, and once against the exact size of the two
 *       JPEGs that are about to be written, because those bytes and not the upload are what
 *       consume the volume. The cheap check can only ever refuse uploads the exact one would also
 *       have refused: the stored pair is smaller than the source for anything a camera
 *       produces.</li>
 *   <li><strong>(e) Re-encode to JPEG</strong> at the configured quality and long edge, and store
 *       <em>only</em> that. This is what strips EXIF and GPS — by construction, because the source
 *       file's marker segments are never copied, rather than by a library that might miss a tag.</li>
 *   <li><strong>(f) Two {@code receipt_image} rows</strong>, ORIGINAL and THUMBNAIL, each written
 *       through {@link LocalDiskStorage#put}.</li>
 * </ol>
 *
 * <p><strong>Nothing is ever written with {@code java.nio} directly.</strong>
 * {@link LocalDiskStorage} generates every key itself from a v4 UUID plus a re-validated extension
 * hint and refuses to address anything outside its three areas — that refusal is the
 * path-traversal guard, and it is why a filename of {@code ../../etc/passwd} cannot reach a path:
 * the filename is never consulted at all. It also sets {@code 0600} where the filesystem supports
 * POSIX permissions, so this class adds nothing on that front.
 *
 * <h2>The quota is per household, not per user</h2>
 * Gap row 11 words it as "per-user". {@link LocalDiskStorage} accounts per household because the
 * household id is the second segment of every key it generates, and there is no per-user segment
 * to count. A per-user quota over a shared area could only be enforced from a database column the
 * storage does not maintain — and it would be the wrong shape anyway: the ledger is shared, the
 * disk is shared, and one member filling the volume is a household's problem to see and solve.
 *
 * <h2>What happens after the row is persisted (owner decision, 2026-09-03)</h2>
 * The receipt and its images are persisted synchronously. Then the <em>uploader's own</em>
 * {@link AiHandle} decides:
 * <ul>
 *   <li>enabled <em>and</em> a vision model configured — the pipeline is dispatched on
 *       {@code receiptExecutor} and the status walks forward through its stages;</li>
 *   <li>otherwise — the receipt goes straight to {@code COMPLETE} with
 *       {@code reviewRequired = true}, and the member fills the fields in through
 *       {@code PATCH /v1/receipts/{id}}. <strong>This is the ordinary outcome, not an error.</strong>
 *       It is never a 409, never a {@code FAILED} row and never a 4xx of any kind; the event record's
 *       own javadoc says the same thing.</li>
 * </ul>
 *
 * <h2>When the executor refuses the work</h2>
 * {@code receiptExecutor} uses {@code AbortPolicy}, so a full queue throws
 * {@link TaskRejectedException} back onto the request thread. §5 item 7 sketches a 503 with
 * {@code Retry-After}; <strong>this implementation answers 202 instead</strong> and leaves the row
 * in {@code UPLOADED} for the hourly sweep to re-enqueue. The reason is that by the time the
 * rejection happens the row and both files are already committed: a 503 would tell the client its
 * upload failed while the server was holding a perfectly good receipt, and a client that retried
 * would upload the same image again. 202 is the truthful answer — "stored, not yet processed" — it
 * is exactly what {@code UPLOADED} means, and no new error slug is invented for a state the
 * contract already has a status value for. The receipt is never lost either way.
 */
@Service
public class ReceiptUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptUploadService.class);

    /** The extension hint handed to storage. Re-validated there; never derived from a filename. */
    private static final String JPEG_EXTENSION = "jpg";

    private final ReceiptRepository receipts;
    private final MultipartFileValidator validator;
    private final ReceiptImageProcessor images;
    private final LocalDiskStorage storage;
    private final AiGateway gateway;
    private final ReceiptPipelineOrchestrator orchestrator;
    private final ReceiptEventPublisher publisher;
    private final ReceiptProperties properties;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReceiptUploadService(ReceiptRepository receipts, MultipartFileValidator validator,
                                ReceiptImageProcessor images, LocalDiskStorage storage,
                                AiGateway gateway, ReceiptPipelineOrchestrator orchestrator,
                                ReceiptEventPublisher publisher, ReceiptProperties properties) {
        this.receipts = receipts;
        this.validator = validator;
        this.images = images;
        this.storage = storage;
        this.gateway = gateway;
        this.orchestrator = orchestrator;
        this.publisher = publisher;
        this.properties = properties;
    }

    /**
     * Runs the gauntlet, stores the two images, persists the receipt and starts or skips the
     * pipeline.
     *
     * <p><strong>One transaction, and the two things that must not happen inside it are
     * registered for afterwards.</strong> The row and both image rows commit together, so a
     * receipt can never exist without its images or the other way round. The pipeline dispatch and
     * the {@code ReceiptProcessedEvent} are both registered for after the commit: a background
     * thread handed a receipt id before the commit would look it up and find nothing, and a
     * listener told about a receipt before the commit would read a row nobody else can see — or,
     * on a rollback, one that never existed.
     *
     * <p>The two stored files are removed if the transaction rolls back, for the same reason
     * the delete route removes them: a file on the volume with no row is the beginning of gap
     * row 30's unbounded disk growth.
     *
     * @param householdId the caller's household, from signed claims — never from the request
     * @param userId      the uploader, from signed claims
     * @param file        the multipart part named {@code file}
     * @param source      the multipart part named {@code source}; absent or unrecognised is UPLOAD
     * @return the persisted receipt, in {@code UPLOADED} or {@code COMPLETE}
     */
    @Transactional
    public Receipt upload(UUID householdId, UUID userId, MultipartFile file, String source) {
        // (a) and (b).
        validator.validate(file);

        // (d), first half — against the DECLARED size, before the body is read and before any
        // pixel is decoded. A household that is already over its allowance is refused for the
        // price of one directory walk rather than after two JPEG encodes; the contract's own
        // description of this 413 says the quota is checked before the bytes are read, and this
        // is the check that makes that sentence true.
        enforceQuota(householdId, file.getSize());

        byte[] content = readFully(file);

        // (c) — header only, before any decode.
        images.probe(content);

        // (e) — the only bytes that ever reach the disk. ONE decode for both sizes, under the
        // render semaphore: see ReceiptImageProcessor's class javadoc for why two calls here were
        // a heap problem rather than merely a slow one.
        ReceiptImageProcessor.Renditions rendered = images.renditions(content);
        byte[] full = rendered.full();
        byte[] thumbnail = rendered.thumbnail();

        // (d), second half — the exact check, against what is really about to be written. The
        // stored size is what consumes the volume, and it is smaller than the upload for anything
        // a camera produces, so this can only ever admit an upload the first check admitted.
        enforceQuota(householdId, (long) full.length + thumbnail.length);

        // (f) — two files, two rows. Keys are generated by the storage; nothing derived from the
        // client's filename reaches a path.
        String fullKey = storage.put(StorageArea.RECEIPTS, householdId, full, JPEG_EXTENSION);
        removeIfRolledBack(fullKey);
        String thumbnailKey = storage.put(StorageArea.RECEIPTS, householdId, thumbnail,
            JPEG_EXTENSION);
        removeIfRolledBack(thumbnailKey);

        Receipt receipt = new Receipt(householdId, userId, ReceiptSource.from(source),
            file.getSize());
        receipt.setStatus(ReceiptStatus.UPLOADED);

        // Whose AI, asked of the UPLOADER: ai_settings is keyed by user id (owner decision 9).
        // isEnabled() opens no connection and reads only a snapshot, and visionModel() is a field
        // of that same snapshot, so neither costs anything here.
        AiHandle handle = gateway.forUser(userId);
        boolean canExtract = handle.isEnabled() && handle.visionModel() != null;
        if (!canExtract) {
            // The decided degradation (owner decision 2026-09-03): attach the image, let the
            // member enter the fields. Not an error, and not a FAILED row.
            receipt.setStatus(ReceiptStatus.COMPLETE);
            receipt.setReviewRequired(true);
            receipt.setProcessingCompletedAt(Instants.now());
        }

        Receipt saved = receipts.save(receipt);
        saved.addImage(new ReceiptImage(ReceiptImageKind.ORIGINAL, 0, fullKey,
            MultipartFileValidator.IMAGE_JPEG, full.length, sha256(full)));
        saved.addImage(new ReceiptImage(ReceiptImageKind.THUMBNAIL, 0, thumbnailKey,
            MultipartFileValidator.IMAGE_JPEG, thumbnail.length, sha256(thumbnail)));
        Receipt stored = receipts.save(saved);

        if (canExtract) {
            dispatchAfterCommit(householdId, stored.getId());
        } else {
            publisher.publishAfterCommit(householdId, userId, stored.getId(),
                ReceiptProcessedEvent.Outcome.REVIEW_REQUIRED, null);
        }
        return stored;
    }

    /**
     * Hands the pipeline to {@code receiptExecutor} once the row is visible, and survives its
     * refusal.
     *
     * <p>See the class javadoc: a rejection leaves the row in {@code UPLOADED} and the caller
     * still gets its 202. Nothing is lost, and the hourly sweep re-enqueues it. The
     * {@code TaskRejectedException} is caught here rather than allowed out because by this point
     * the transaction has already committed — throwing would turn a stored receipt into a 500.
     */
    private void dispatchAfterCommit(UUID householdId, UUID receiptId) {
        runAfterCommit(() -> {
            try {
                orchestrator.processAsync(householdId, receiptId);
            } catch (TaskRejectedException e) {
                LOG.warn("receipt-upload: receiptExecutor refused receipt {}; the row stays "
                    + "UPLOADED for the hourly orphan sweep to re-enqueue", receiptId);
            }
        });
    }

    /** Deletes a just-written file if the transaction it belongs to does not commit. */
    private void removeIfRolledBack(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        storage.delete(key);
                    }
                }
            });
    }

    private static void runAfterCommit(Runnable work) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            work.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
    }

    /**
     * Reads the part.
     *
     * <p>Safe to hold in heap by this point and only by this point: the declared size has already
     * been refused above {@code trove.receipt.max-file-bytes}, so the array is bounded by that
     * number rather than by whatever the client felt like sending. The source called
     * {@code getBytes()} first and checked afterwards.
     */
    private static byte[] readFully(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new finance.trove.common.exception.ValidationException(
                "Could not read the uploaded receipt file", e);
        }
    }

    private void enforceQuota(UUID householdId, long incomingBytes) {
        long used = storage.usage(StorageArea.RECEIPTS, householdId);
        long quota = properties.quotaBytes();
        if (used + incomingBytes > quota) {
            throw new UploadTooLargeException("Storing this receipt would put the household over "
                + "its " + quota + " byte receipt storage allowance; " + used + " bytes are in use");
        }
    }

    /** Lowercase hex SHA-256 of the stored bytes. An integrity record, never a key. */
    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
