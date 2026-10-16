package finance.trove.receipts;

import finance.trove.common.storage.LocalDiskStorage;
import finance.trove.common.storage.StorageArea;
import finance.trove.common.time.UserClock;
import finance.trove.receipts.pipeline.ReceiptPipelineOrchestrator;
import finance.trove.receipts.receipt.Receipt;
import finance.trove.receipts.receipt.ReceiptRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The hourly receipt sweep ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5 items 6 and 13; gap
 * row 30).
 *
 * <p>A component of its own rather than a sixth method on {@code PurgeJobs}, following
 * {@code TransferPairingJob}: that class sweeps append-only tables and this one restarts work and
 * deletes files, so the two have different blast radii and belong under different reviews. It
 * copies that class's shape — a UTC cron and a distinct ShedLock name — because those are the two
 * decisions that matter and are easy to get wrong.
 *
 * <p><strong>The lock name is fixed by §5 item 6 and nothing else in the tree holds it.</strong>
 * ShedLock keys on the name alone, so a name shared with one of {@code PurgeJobs}' five would make
 * this job silently never run. {@code ScheduledJobsTest} scans every compiled class for
 * {@code @Scheduled} and fails the build on a duplicate lock name, a missing lock, or a cron
 * without {@code zone = "UTC"}.
 *
 * <h2>Two halves, and each one closes a real leak</h2>
 * <ol>
 *   <li><strong>Stuck receipts are re-enqueued.</strong> A receipt in a non-terminal status past
 *       {@code trove.receipt.stuck-after} is one whose run died — the process was restarted
 *       mid-pipeline, or {@code receiptExecutor} refused the work under {@code AbortPolicy} and the
 *       upload left the row {@code UPLOADED} on purpose. Without this the member would poll a
 *       status that never moves, for ever.</li>
 *   <li><strong>Files with no row are deleted.</strong> A receipt file that no {@code receipt_image}
 *       row names is unreachable from every route and invisible to the quota's accounting only in
 *       the sense that it still consumes it. Gap row 30 is unbounded disk growth, and this is the
 *       half of the answer that the re-encode and the quota do not cover.</li>
 * </ol>
 *
 * <h2>Interruption is checked between rows</h2>
 * §5 item 13: {@code server.shutdown=graceful} gives in-flight work twenty seconds, and a sweep
 * that walks the whole volume must notice that its thread has been interrupted rather than holding
 * the JVM open past it. Both loops check, and both stop where they are — the work is idempotent, so
 * the next hour's pass finishes what this one did not.
 *
 * <p>Returns {@code void} because ShedLock refuses to wrap a method with a return value: it cannot
 * say what a locked-out invocation should answer. The counts go to the log and the tests assert
 * against the database and the volume.
 */
@Component
public class ReceiptOrphanSweep {

    /**
     * The ShedLock name §5 item 6 fixes. Distinct from every entry of {@code PurgeJobs.LOCK_NAMES}
     * and from {@code TransferPairingJob}'s, and {@code ScheduledJobsTest} fails the build if that
     * ever stops being true.
     */
    public static final String LOCK_NAME = "receipt-orphan-sweep";

    private static final Logger LOG = LoggerFactory.getLogger(ReceiptOrphanSweep.class);

    /**
     * How many stuck receipts one pass restarts.
     *
     * <p>Bounded because the pool it hands them to is three threads deep with a hundred queue
     * slots: dispatching a thousand at once would abort most of them and achieve nothing that the
     * next hour's pass would not. Twenty an hour drains any realistic backlog within a day.
     */
    static final int MAX_REQUEUED_PER_PASS = 20;

    /**
     * How long a stored file must have existed before its absence from the database counts as
     * orphaned.
     *
     * <p>Not optional and not merely cautious: the upload writes both files <em>before</em> its
     * transaction commits, so for a moment there is legitimately a file on disk that no row names.
     * A sweep without this window would delete the receipt a member is uploading right now.
     */
    static final java.time.Duration ORPHAN_GRACE = java.time.Duration.ofHours(1);

    private final ReceiptRepository receipts;
    private final ReceiptPipelineOrchestrator orchestrator;
    private final LocalDiskStorage storage;
    private final ReceiptProperties properties;
    private final UserClock clock;

    public ReceiptOrphanSweep(ReceiptRepository receipts,
                              ReceiptPipelineOrchestrator orchestrator, LocalDiskStorage storage,
                              ReceiptProperties properties, UserClock clock) {
        this.receipts = receipts;
        this.orchestrator = orchestrator;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Hourly at twenty past, UTC.
     *
     * <p>Twenty past rather than on the hour so the sweep does not contend with
     * {@code TransferPairingJob} at ten past on a single-CPU container.
     *
     * <p><strong>{@code lockAtMostFor} is fifty minutes, and the arithmetic is deliberate.</strong>
     * A re-enqueued receipt runs its pipeline <em>synchronously</em> here rather than being handed
     * back to {@code receiptExecutor} — a pool whose {@code AbortPolicy} is exactly what left some
     * of these rows stuck in the first place, so bouncing them off it again would be a loop that
     * makes no progress. The longest a pass can therefore take is
     * {@link #MAX_REQUEUED_PER_PASS} (20) times one vision call's connect plus read timeout: 5 s
     * connect (the per-user default, and the ceiling at the default
     * {@code TROVE_HTTP_CONNECT_TIMEOUT_MS} of 5000) plus 120 s read (the per-user default
     * {@code ai_settings.read_timeout_ms} and, at the default {@code TROVE_HTTP_READ_TIMEOUT_MS}
     * of 30000, also the ceiling, because {@code AiTimeouts} caps a member's value at four times
     * that setting). So 20 x 125 s = 41.7 minutes, if every one of them hangs until the timeout.
     * A normal pass is seconds. Fifty minutes is above that worst case with 8.3 minutes to spare
     * and below the hourly cadence, so
     * a lock cannot expire under a running pass and two passes cannot overlap. That margin is
     * only as good as the arithmetic: raising {@code MAX_REQUEUED_PER_PASS} or the AI read
     * timeout ({@code TROVE_HTTP_READ_TIMEOUT_MS} moves the ceiling) means revisiting
     * {@code lockAtMostFor}.
     */
    @Scheduled(cron = "0 20 * * * *", zone = "UTC")
    @SchedulerLock(name = LOCK_NAME, lockAtMostFor = "PT50M")
    public void sweep() {
        int requeued = requeueStuckReceipts();
        int deleted = deleteOrphanedFiles();
        LOG.info("receipt-orphan-sweep re-enqueued {} stuck receipts and deleted {} orphaned files",
            requeued, deleted);
    }

    /**
     * Re-enqueues receipts left in a non-terminal status.
     *
     * @return how many were handed back to the pipeline
     */
    public int requeueStuckReceipts() {
        Instant cutoff = clock.now().minus(properties.stuckAfter());
        List<Receipt> stuck = receipts.findStuckBefore(cutoff);
        int requeued = 0;
        for (Receipt receipt : stuck) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.info("receipt-orphan-sweep: interrupted after re-enqueuing {}; the next pass "
                    + "picks up the rest", requeued);
                return requeued;
            }
            if (requeued >= MAX_REQUEUED_PER_PASS) {
                break;
            }
            orchestrator.run(receipt.getHouseholdId(), receipt.getId());
            requeued++;
        }
        return requeued;
    }

    /**
     * Deletes stored receipt files that no {@code receipt_image} row names.
     *
     * <p>The set of known keys is read once, before the walk, and the walk deletes only files older
     * than {@link #ORPHAN_GRACE}. Reading the database first and the disk second is the safe order:
     * a receipt uploaded during the walk has its row already, so its file cannot be mistaken for an
     * orphan — the other order could see the file before the row existed.
     *
     * @return how many files were removed
     */
    public int deleteOrphanedFiles() {
        Set<String> known = new HashSet<>(receipts.findAllStoredKeys());
        Path area = storage.root().resolve(StorageArea.RECEIPTS.directoryName());
        if (!Files.isDirectory(area)) {
            return 0;
        }
        Instant olderThan = clock.now().minus(ORPHAN_GRACE);
        int deleted = 0;
        try (Stream<Path> files = Files.walk(area)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                if (Thread.currentThread().isInterrupted()) {
                    LOG.info("receipt-orphan-sweep: interrupted after deleting {} orphaned files; "
                        + "the next pass picks up the rest", deleted);
                    return deleted;
                }
                String key = keyOf(area, file);
                if (known.contains(key)) {
                    continue;
                }
                if (Files.getLastModifiedTime(file).toInstant().isAfter(olderThan)) {
                    // Written within the grace window: it may belong to an upload whose
                    // transaction has not committed yet.
                    continue;
                }
                if (storage.delete(key)) {
                    deleted++;
                }
            }
        } catch (IOException | UncheckedIOException e) {
            // A volume that cannot be walked is an operator's problem, not a reason to keep the
            // scheduler retrying inside this pass. Nothing about the path is logged.
            LOG.warn("receipt-orphan-sweep: could not walk the receipts area; {} files deleted "
                + "before it stopped", deleted);
        }
        return deleted;
    }

    /**
     * The storage key of a file under the receipts area.
     *
     * <p>Rebuilt as {@code receipts/<household>/<file>} rather than passed as a path, because
     * {@link LocalDiskStorage#delete} takes a key and re-checks it against its own root — which is
     * the path-traversal guard, and which a sweep that deleted by {@code Files.delete} would step
     * straight around.
     */
    private static String keyOf(Path area, Path file) {
        return StorageArea.RECEIPTS.directoryName() + "/"
            + area.relativize(file).toString().replace(java.io.File.separatorChar, '/');
    }
}
