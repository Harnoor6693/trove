package finance.trove.receipts.upload;

import finance.trove.common.exception.ValidationException;
import finance.trove.receipts.ReceiptProperties;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.stereotype.Component;

/**
 * Clauses (c) and (e) of the upload gauntlet: the header-only dimension probe, and the re-encode
 * that is the only thing ever written to disk
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 11; §2.6).
 *
 * <h2>The probe runs before the decode, and that ordering is the whole point</h2>
 * The source called {@code ImageIO.read} on the full byte array and never looked at the dimensions
 * at all ({@code ReceiptUploadService.java:104}). A PNG declaring 60000&times;60000 is a few
 * kilobytes on the wire — the format compresses a flat colour to almost nothing — and about 14 GB
 * of {@code int[]} the instant it is decoded. On a single-JVM install with a 1.5 GB heap that is
 * not a failed request, it is the application. {@link #probe} therefore opens an
 * {@link ImageReader} and asks it for {@code getWidth}/{@code getHeight}, which read the header
 * and nothing else, and refuses over the megapixel ceiling <em>there</em> — before any pixel is
 * decoded.
 *
 * <h2>The re-encode is how EXIF is stripped</h2>
 * {@link #reencode} decodes to a {@link BufferedImage}, which is pixels and nothing else, scales
 * it, and writes a fresh JPEG from those pixels. Every marker segment the source file carried —
 * {@code APP1}/Exif with its GPS IFD, {@code APP13}/IPTC, XMP, the thumbnail an EXIF block can
 * itself contain — is gone because it was never copied, not because a library was asked to remove
 * it. That is a stronger guarantee than tag stripping: there is no list of tags to be incomplete.
 * Gap row 11 is explicit that nothing in the source stripped EXIF anywhere, and
 * {@code apps/mobile}'s camera hook posts at quality 0.8 with no {@code exif: false} flag today,
 * so this is the only thing standing between a receipt photograph and the coordinates of the
 * member's home.
 *
 * <p>The output is always JPEG, whatever went in. A PNG receipt is stored as a JPEG, which is why
 * the download route declares exactly one media type.
 *
 * <h2>One decode per upload, and only a few decodes at a time</h2>
 * The megapixel ceiling bounds ONE image; it does not bound how many are being decoded at once,
 * and the upload used to ask for two full decodes of every accepted file — one for the stored
 * image and one for the thumbnail. A flat-colour 6250&times;6250 PNG is 39 megapixels, passes the
 * probe, and is a few hundred kilobytes on the wire, so it is nowhere near the 10 MB per-file cap:
 * about 150 MB of {@code int[]} per decode, for a request that costs the client almost nothing.
 * The route's own rate limit is 30 requests a minute per caller and Tomcat will serve them on up
 * to 200 threads, so a handful of overlapping uploads reached the same 1.5 GB heap this class's
 * ceiling was added to protect. Two things follow, and both are in {@link #renditions}:
 * <ul>
 *   <li><strong>the source is decoded once</strong> and both sizes are scaled from that one
 *       raster, which halves the cost of every accepted upload; and</li>
 *   <li><strong>a semaphore bounds how many rasters exist at once</strong>
 *       ({@code trove.receipt.max-concurrent-renders}, default 2). A thread that cannot get a
 *       permit waits for one rather than being refused: by this point the caller's bytes are
 *       already in hand and are bounded by the per-file cap, so waiting costs a request thread and
 *       ten megabytes, while proceeding costs a hundred and fifty. The bound is on the expensive
 *       allocation, not on the request.</li>
 * </ul>
 */
@Component
public class ReceiptImageProcessor {

    private final ReceiptProperties properties;

    /**
     * Permits to hold a decoded raster. Fair, so a burst cannot starve the thread that has been
     * waiting longest — an unfair semaphore under load is how a request times out while requests
     * that arrived after it are served.
     */
    private final Semaphore renderPermits;

    private final AtomicInteger rendersInFlight = new AtomicInteger();
    private final AtomicInteger peakRendersInFlight = new AtomicInteger();

    public ReceiptImageProcessor(ReceiptProperties properties) {
        this.properties = properties;
        this.renderPermits = new Semaphore(properties.maxConcurrentRenders(), true);
    }

    /**
     * The most decodes this instance has ever had in flight at once.
     *
     * <p>Exists so that the concurrency bound can be asserted rather than believed: a test that
     * starts more threads than there are permits and reads this back is the only way to prove the
     * semaphore is actually holding, and "the code has a semaphore in it" is not that proof.
     */
    public int peakConcurrentRenders() {
        return peakRendersInFlight.get();
    }

    /**
     * Reads the image's declared dimensions from its header and refuses an image too large to
     * decode.
     *
     * <p>Nothing here decodes a pixel: {@link ImageReader#getWidth(int)} and
     * {@link ImageReader#getHeight(int)} parse the metadata the format puts at the front. The
     * reader is disposed and the stream closed on every path, including the refusal, because an
     * undisposed {@code ImageReader} holds native memory that the request thread's exit does not
     * free.
     *
     * @param content the whole uploaded file, already accepted by {@link MultipartFileValidator}
     * @return the image's dimensions
     * @throws ValidationException when no reader recognises the bytes, the header is unreadable,
     *                             or the declared pixel count exceeds the configured ceiling
     */
    public Dimensions probe(byte[] content) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(
            new java.io.ByteArrayInputStream(content))) {

            if (stream == null) {
                throw new ValidationException("Could not read the uploaded receipt image");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw new ValidationException(
                    "The uploaded receipt image is not in a format this server can read");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * (long) height;
                if (pixels > properties.maxPixels()) {
                    // (c) Refused HERE, from the header, before any decode. See the class javadoc.
                    throw new ValidationException("The uploaded receipt image is too large to "
                        + "process: " + width + "x" + height + " pixels exceeds the "
                        + properties.maxMegapixels() + " megapixel limit");
                }
                if (width < 1 || height < 1) {
                    throw new ValidationException("The uploaded receipt image has no pixels");
                }
                return new Dimensions(width, height);
            } catch (IOException e) {
                throw new ValidationException(
                    "Could not read the uploaded receipt image's dimensions", e);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new ValidationException("Could not read the uploaded receipt image", e);
        }
    }

    /**
     * Decodes, scales to at most {@code longEdgePx}, and writes a fresh JPEG.
     *
     * <p>Never upscales: an image already inside the bound is re-encoded at its own size, so a
     * small receipt does not become a large blurry one.
     *
     * @param content    the source bytes, already probed
     * @param longEdgePx the maximum length of the longer edge of the result
     * @return a JPEG carrying pixels and no metadata
     */
    public byte[] reencode(byte[] content, int longEdgePx) {
        return withRenderPermit(() -> {
            BufferedImage source = decode(content);
            return writeJpeg(scale(source, longEdgePx));
        });
    }

    /**
     * The stored image and its thumbnail, from ONE decode of the source.
     *
     * <p>This is what the upload calls. Two calls to {@link #reencode} would decode the same
     * source twice — see the class javadoc — and there is nothing to be gained by it: the
     * thumbnail is scaled from the same raster the full-size image is scaled from, and the source
     * is dropped the moment both are written.
     *
     * @param content the source bytes, already probed
     * @return the two JPEGs, in the sizes the properties record configures
     */
    public Renditions renditions(byte[] content) {
        return withRenderPermit(() -> {
            BufferedImage source = decode(content);
            byte[] full = writeJpeg(scale(source, properties.maxLongEdgePx()));
            byte[] thumbnail = writeJpeg(scale(source, properties.thumbnailPx()));
            return new Renditions(full, thumbnail);
        });
    }

    /**
     * Runs one decode under a permit, counting what is in flight.
     *
     * <p>An interrupt while waiting is a shutting-down JVM, not a bad request: the flag is
     * restored and the upload fails loudly rather than being turned into a 4xx that blames the
     * member.
     */
    private <T> T withRenderPermit(java.util.function.Supplier<T> work) {
        try {
            renderPermits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while waiting to decode a receipt image", e);
        }
        int inFlight = rendersInFlight.incrementAndGet();
        peakRendersInFlight.accumulateAndGet(inFlight, Math::max);
        try {
            return work.get();
        } finally {
            rendersInFlight.decrementAndGet();
            renderPermits.release();
        }
    }

    private static BufferedImage decode(byte[] content) {
        BufferedImage image;
        try {
            image = ImageIO.read(new java.io.ByteArrayInputStream(content));
        } catch (IOException e) {
            throw new ValidationException("Could not decode the uploaded receipt image", e);
        }
        if (image == null) {
            throw new ValidationException("Could not decode the uploaded receipt image");
        }
        return image;
    }

    private static BufferedImage scale(BufferedImage source, int longEdgePx) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = Math.min(1.0d,
            (double) longEdgePx / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));

        // TYPE_INT_RGB rather than ARGB: the output is JPEG, which has no alpha channel, and
        // handing a writer an image that has one produces either a failure or a colour-shifted
        // file depending on the plugin. Flattening here makes the result the same everywhere.
        BufferedImage target =
            new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
            // Paints the white ground a transparent PNG would otherwise leave as black.
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, targetWidth, targetHeight);
            graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    /**
     * Writes the pixels as a JPEG at the configured quality.
     *
     * <p>{@code ImageIO.write} would use the plugin's default quality and give no control; the
     * writer is obtained explicitly so that §2.6's q85 is the number that is actually applied.
     * Nothing but the raster is passed to {@link ImageWriter#write}: the {@link IIOImage} is
     * constructed with null metadata, which is the second place the source file's EXIF would have
     * had to be copied and is not.
     */
    private byte[] writeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException(
                "No JPEG writer is available in this JVM's ImageIO registry");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality((float) (double) properties.jpegQuality());
            }
            writer.write(null, new IIOImage(image, null, null), params);
            out.flush();
        } catch (IOException e) {
            throw new ValidationException("Could not re-encode the uploaded receipt image", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /**
     * The two JPEGs one upload stores: the image itself and its thumbnail.
     *
     * @param full      the stored image, at most {@code trove.receipt.max-long-edge-px} on its
     *                  long edge
     * @param thumbnail the thumbnail, at most {@code trove.receipt.thumbnail-px}
     */
    public record Renditions(byte[] full, byte[] thumbnail) {
    }

    /** An image's declared size, from its header. */
    public record Dimensions(int width, int height) {

        public long pixels() {
            return (long) width * (long) height;
        }
    }
}
