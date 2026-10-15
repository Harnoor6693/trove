package finance.trove.receipts;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every number the receipt upload and its sweep can be tuned by, in one place
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.6, §5 item 6; gap row 11).
 *
 * <p><strong>Every value carries its own default here and nothing is added to
 * {@code application.yml}.</strong> Collapse steps 11, 13, 14 and 16 run in parallel worktrees and
 * no step of this wave owns that file, so a property added to it is a merge conflict at best and a
 * silently lost edit at worst. A record bound by {@code @ConfigurationPropertiesScan} with
 * defaults in its canonical constructor needs no yml entry to work and picks one up the moment an
 * operator adds it — the shape {@code AiCategorisationProperties} and
 * {@code PurgeJobs.reloadRateLimits} already use. The keys are named in this step's merge notes
 * with the environment variables §1.6 wants them bound to, so an operator can promote them.
 *
 * <h2>The prefix is {@code trove.receipt}, singular, and that one letter is load-bearing</h2>
 * Spring's relaxed environment binding maps a property path to an environment variable by
 * uppercasing each dot-separated element and joining them with {@code _}, so
 * {@code trove.receipt.quota-mb} can be set by {@code TROVE_RECEIPT_QUOTA_MB} and by nothing else.
 * The operator-facing variable that {@code deploy/.env.example},
 * {@code deploy/docker-compose.yml} and {@code deploy/README.md} all define and pass into the
 * container is exactly {@code TROVE_RECEIPT_QUOTA_MB}. A prefix of {@code trove.receipts} would
 * have made every knob on this record <strong>silently inert</strong>: an operator editing the
 * documented variable would have got no effect and no error, and the quota would have stayed at
 * its default for ever. Singular also matches the house style of {@code trove.import}. §1.6 of the
 * plan pairs {@code TROVE_RECEIPT_QUOTA_MB} with a <em>plural</em> property name; that pairing is
 * the mistake and this record is the side that ships, so <strong>do not "correct" this prefix to
 * the plural of the package name</strong>. The Java package stays
 * {@code finance.trove.receipts}: a package name binds nothing.
 *
 * <p>Defaults, and why each is what it is:
 * <ul>
 *   <li>{@code trove.receipt.quota-mb} — {@code 2048}, §1.6's figure, bound to
 *       {@code TROVE_RECEIPT_QUOTA_MB}. <strong>It is accounted per HOUSEHOLD, not per user</strong>,
 *       and that is a deviation from gap row 11's wording with a reason:
 *       {@code LocalDiskStorage.usage(area, householdId)} works because the household id is the
 *       second segment of every key it generates, and there is no per-user segment to count. A
 *       per-user quota over a shared area could only be enforced by summing a database column that
 *       the storage does not maintain, and it would be the wrong shape anyway — the ledger is
 *       shared, the disk is shared, and one member filling the volume is a household problem.</li>
 *   <li>{@code trove.receipt.max-file-bytes} — 10 MB. Smaller than the container's 20 MB
 *       multipart cap (§1.6) on purpose: this one is checked against the part's <em>declared</em>
 *       size before a byte of the body is read, so the cheap refusal happens first and the
 *       container's cap is the backstop for a client that lies about the size.</li>
 *   <li>{@code trove.receipt.max-megapixels} — {@code 40}. The dimension ceiling gap row 11 asks
 *       for, enforced from an {@code ImageReader} <em>header</em> read before any full decode. A
 *       crafted 60000&times;60000 PNG is a few kilobytes on the wire and 14 GB of {@code int[]}
 *       once decoded; on a single-JVM install with a 1.5 GB heap that is the whole application,
 *       not one request. Forty megapixels is far above any phone camera and far below anything
 *       that can be decoded by accident.</li>
 *   <li>{@code trove.receipt.max-long-edge-px} — {@code 2000}, §2.6's figure. Every accepted
 *       image is re-encoded to at most this on its long edge, which is what makes the stored file
 *       both small and EXIF-free.</li>
 *   <li>{@code trove.receipt.thumbnail-px} — {@code 400}, the source's figure, kept.</li>
 *   <li>{@code trove.receipt.max-concurrent-renders} — {@code 2}. How many uploaded images may be
 *       held as decoded rasters at the same instant. The megapixel ceiling bounds one image at
 *       about 150 MB of {@code int[]}; nothing bounded how many of those existed at once, and the
 *       upload route's rate limit (30 a minute per caller) plus Tomcat's thread pool let a handful
 *       of legal uploads meet on the same 1.5 GB heap. Two permits is the product of that
 *       arithmetic — enough that ordinary phone photographs never queue, few enough that the worst
 *       legal case is a few hundred megabytes rather than the JVM.</li>
 *   <li>{@code trove.receipt.jpeg-quality} — {@code 0.85}, §2.6's q85.</li>
 *   <li>{@code trove.receipt.stuck-after} — {@code PT1H}. How long a receipt may sit in a
 *       non-terminal status before the hourly sweep calls it stuck and re-enqueues it. An hour is
 *       chosen against the work: the longest thing the pipeline does is one vision call, whose own
 *       timeout is minutes, so an hour is far past "still running" and short enough that a receipt
 *       orphaned by a restart is picked up on the next pass rather than the next day.</li>
 * </ul>
 *
 * <p>A record validated in its canonical constructor, which {@code ArchitectureTest} requires: a
 * bad value fails the context refresh instead of surfacing on the first upload nobody is watching.
 *
 * @param quotaMb        the household's total receipt storage allowance, in megabytes
 * @param maxFileBytes   the largest single upload accepted, checked against the declared size
 * @param maxMegapixels  the largest source image decoded at all, from its header
 * @param maxLongEdgePx  the long edge every stored image is re-encoded down to
 * @param thumbnailPx    the long edge of the generated thumbnail
 * @param jpegQuality    the JPEG quality every stored image is re-encoded at
 * @param maxConcurrentRenders how many uploads may hold a decoded raster at the same instant
 * @param stuckAfter     how long a non-terminal receipt may sit before the sweep re-enqueues it
 */
@ConfigurationProperties("trove.receipt")
public record ReceiptProperties(Integer quotaMb, Long maxFileBytes, Integer maxMegapixels,
                                Integer maxLongEdgePx, Integer thumbnailPx, Double jpegQuality,
                                Integer maxConcurrentRenders, Duration stuckAfter) {

    /** §1.6's figure for {@code TROVE_RECEIPT_QUOTA_MB}. */
    public static final int DEFAULT_QUOTA_MB = 2048;

    /** Half the container's multipart cap, so the cheap declared-size refusal happens first. */
    public static final long DEFAULT_MAX_FILE_BYTES = 10L * 1024 * 1024;

    /** Gap row 11's decode ceiling, enforced from the header. */
    public static final int DEFAULT_MAX_MEGAPIXELS = 40;

    /** §2.6's long edge for the stored image. */
    public static final int DEFAULT_MAX_LONG_EDGE_PX = 2000;

    /** The source's thumbnail size, kept. */
    public static final int DEFAULT_THUMBNAIL_PX = 400;

    /** §2.6's q85. */
    public static final double DEFAULT_JPEG_QUALITY = 0.85d;

    /** Two rasters at 40 megapixels each is a few hundred megabytes, not the heap. */
    public static final int DEFAULT_MAX_CONCURRENT_RENDERS = 2;

    /** Well past one vision call, well short of a day. */
    public static final Duration DEFAULT_STUCK_AFTER = Duration.ofHours(1);

    public ReceiptProperties {
        quotaMb = quotaMb == null ? DEFAULT_QUOTA_MB : quotaMb;
        maxFileBytes = maxFileBytes == null ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
        maxMegapixels = maxMegapixels == null ? DEFAULT_MAX_MEGAPIXELS : maxMegapixels;
        maxLongEdgePx = maxLongEdgePx == null ? DEFAULT_MAX_LONG_EDGE_PX : maxLongEdgePx;
        thumbnailPx = thumbnailPx == null ? DEFAULT_THUMBNAIL_PX : thumbnailPx;
        jpegQuality = jpegQuality == null ? DEFAULT_JPEG_QUALITY : jpegQuality;
        maxConcurrentRenders =
            maxConcurrentRenders == null ? DEFAULT_MAX_CONCURRENT_RENDERS : maxConcurrentRenders;
        stuckAfter = stuckAfter == null ? DEFAULT_STUCK_AFTER : stuckAfter;

        if (quotaMb < 1) {
            throw new IllegalArgumentException(
                "trove.receipt.quota-mb must be at least 1; a quota of zero refuses every upload "
                    + "on an install that thinks receipts are switched on. Got " + quotaMb);
        }
        if (maxFileBytes < 1024) {
            throw new IllegalArgumentException(
                "trove.receipt.max-file-bytes must be at least 1024, got " + maxFileBytes);
        }
        if (maxMegapixels < 1) {
            throw new IllegalArgumentException(
                "trove.receipt.max-megapixels must be at least 1, got " + maxMegapixels);
        }
        if (maxLongEdgePx < 64) {
            throw new IllegalArgumentException(
                "trove.receipt.max-long-edge-px must be at least 64; below that the stored image "
                    + "is unreadable and the upload has destroyed the member's data. Got "
                    + maxLongEdgePx);
        }
        if (thumbnailPx < 16 || thumbnailPx > maxLongEdgePx) {
            throw new IllegalArgumentException(
                "trove.receipt.thumbnail-px must be between 16 and max-long-edge-px ("
                    + maxLongEdgePx + "), got " + thumbnailPx);
        }
        if (jpegQuality <= 0d || jpegQuality > 1d) {
            throw new IllegalArgumentException(
                "trove.receipt.jpeg-quality must be a fraction in (0, 1], got " + jpegQuality);
        }
        if (maxConcurrentRenders < 1) {
            throw new IllegalArgumentException(
                "trove.receipt.max-concurrent-renders must be at least 1; zero permits would "
                    + "block every upload for ever. Got " + maxConcurrentRenders);
        }
        if (stuckAfter.isNegative() || stuckAfter.isZero()) {
            throw new IllegalArgumentException(
                "trove.receipt.stuck-after must be positive; zero would make the sweep re-enqueue "
                    + "receipts that are still being processed. Got " + stuckAfter);
        }
    }

    /** The household allowance in bytes, which is what {@code LocalDiskStorage.usage} returns. */
    public long quotaBytes() {
        return quotaMb * 1024L * 1024L;
    }

    /** The megapixel ceiling as a pixel count, which is what a header probe compares against. */
    public long maxPixels() {
        return maxMegapixels * 1_000_000L;
    }
}
