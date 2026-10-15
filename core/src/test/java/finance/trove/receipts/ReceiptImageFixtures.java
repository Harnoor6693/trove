package finance.trove.receipts;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;

/**
 * Real image bytes for the upload tests — not mocks, not fixtures on disk.
 *
 * <p>Every clause of the upload gauntlet is about the <em>content</em> of a file, so a test that
 * handed the code a mocked reader or a byte array of zeroes would be testing its own test double.
 * These build genuine JPEG and PNG streams, including a crafted PNG header that declares
 * sixty thousand pixels a side without carrying them and a JPEG that really contains an EXIF GPS
 * tag.
 */
public final class ReceiptImageFixtures {

    private ReceiptImageFixtures() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** A real JPEG of the given size, written by ImageIO. */
    public static byte[] jpeg(int width, int height) {
        return encode(image(width, height), "jpeg");
    }

    /** A real PNG of the given size. */
    public static byte[] png(int width, int height) {
        return encode(image(width, height), "png");
    }

    /**
     * A JPEG carrying an EXIF {@code APP1} segment with a GPS IFD in it.
     *
     * <p>Built by splicing a real {@code APP1} segment in after the SOI marker of a real JPEG,
     * which is exactly where a camera writes one. The test that uses this asserts that no
     * {@code APP1} segment survives the upload — the point being that the server strips EXIF by
     * re-encoding from pixels rather than by removing tags it knows about, so it cannot miss one.
     */
    public static byte[] jpegWithGpsExif(int width, int height) {
        byte[] base = jpeg(width, height);
        byte[] app1 = exifApp1WithGps();
        ByteArrayOutputStream out = new ByteArrayOutputStream(base.length + app1.length);
        // SOI is the first two bytes; the APP1 segment goes straight after it.
        out.write(base, 0, 2);
        out.write(app1, 0, app1.length);
        out.write(base, 2, base.length - 2);
        return out.toByteArray();
    }

    /** Whether a JPEG stream carries any {@code APP1} (EXIF/XMP) marker segment. */
    public static boolean hasApp1Segment(byte[] jpeg) {
        for (int i = 0; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xE1) {
                return true;
            }
        }
        return false;
    }

    /** Whether the bytes contain the ASCII {@code Exif} identifier an EXIF header begins with. */
    public static boolean containsExifIdentifier(byte[] content) {
        byte[] needle = {'E', 'x', 'i', 'f', 0, 0};
        outer:
        for (int i = 0; i + needle.length <= content.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (content[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * A PNG whose {@code IHDR} declares {@code width}&times;{@code height} but which carries a
     * one-pixel image.
     *
     * <p><strong>This is the crafted header the dimension test needs</strong>, and it has to be
     * crafted rather than generated: a genuine 60000&times;60000 PNG cannot be produced by this
     * JVM without the out-of-memory the code under test exists to prevent. A few hundred bytes on
     * the wire that claim 3.6 billion pixels is precisely the attack, and an {@code ImageReader}
     * header probe answers the declared dimensions without decoding anything — which is what makes
     * the refusal possible at all.
     */
    public static byte[] pngDeclaring(int width, int height) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(signature, 0, signature.length);
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(width);
        ihdr.putInt(height);
        ihdr.put((byte) 8);   // bit depth
        ihdr.put((byte) 2);   // colour type: truecolour
        ihdr.put((byte) 0);   // compression
        ihdr.put((byte) 0);   // filter
        ihdr.put((byte) 0);   // interlace
        writeChunk(out, "IHDR", ihdr.array());
        // No IDAT: the header probe never reaches it, and a decode would fail — which is the
        // point. The refusal must happen before anything tries.
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        ByteBuffer length = ByteBuffer.allocate(4).putInt(data.length);
        out.write(length.array(), 0, 4);
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.write(typeBytes, 0, typeBytes.length);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        ByteBuffer checksum = ByteBuffer.allocate(4).putInt((int) crc.getValue());
        out.write(checksum.array(), 0, 4);
    }

    /**
     * A minimal but genuine EXIF {@code APP1} segment whose IFD0 points at a GPS IFD carrying a
     * latitude reference of {@code N}.
     */
    private static byte[] exifApp1WithGps() {
        ByteArrayOutputStream tiff = new ByteArrayOutputStream();
        // TIFF header, big-endian, IFD0 at offset 8.
        write(tiff, new byte[] {'M', 'M', 0, 42, 0, 0, 0, 8});

        // IFD0: one entry, the GPS IFD pointer (0x8825), pointing at offset 26.
        write(tiff, shortBytes(1));
        write(tiff, shortBytes(0x8825));
        write(tiff, shortBytes(4));          // LONG
        write(tiff, intBytes(1));
        write(tiff, intBytes(26));
        write(tiff, intBytes(0));            // next IFD: none

        // GPS IFD at 26: one entry, GPSLatitudeRef (0x0001), ASCII "N".
        write(tiff, shortBytes(1));
        write(tiff, shortBytes(0x0001));
        write(tiff, shortBytes(2));          // ASCII
        write(tiff, intBytes(2));
        write(tiff, new byte[] {'N', 0, 0, 0});
        write(tiff, intBytes(0));

        byte[] payload = tiff.toByteArray();
        byte[] identifier = {'E', 'x', 'i', 'f', 0, 0};
        int segmentLength = 2 + identifier.length + payload.length;

        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.write(0xFF);
        segment.write(0xE1);
        segment.write((segmentLength >> 8) & 0xFF);
        segment.write(segmentLength & 0xFF);
        write(segment, identifier);
        write(segment, payload);
        return segment.toByteArray();
    }

    private static byte[] shortBytes(int value) {
        return new byte[] {(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
    }

    private static byte[] intBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static void write(ByteArrayOutputStream out, byte[] bytes) {
        out.write(bytes, 0, bytes.length);
    }

    private static BufferedImage image(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.BLACK);
            graphics.drawString("TOTAL  12.34", 5, Math.min(height - 2, 20));
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
