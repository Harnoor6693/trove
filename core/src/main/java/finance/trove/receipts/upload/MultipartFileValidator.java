package finance.trove.receipts.upload;

import finance.trove.common.exception.ValidationException;
import finance.trove.receipts.ReceiptProperties;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * The first two clauses of the upload gauntlet: the declared size, and the magic bytes
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 11 (b); §2.6).
 *
 * <p>The source class's magic-byte sniffing is kept — it was the one part of the upload path that
 * was right — with three changes:
 *
 * <ol>
 *   <li><strong>The size is checked before the body is read.</strong>
 *       {@link MultipartFile#getSize()} is the declared part size and costs nothing;
 *       {@code getBytes()} materialises the whole upload in heap. The source called
 *       {@code getBytes()} inside its magic-byte check, so a 20 MB file was fully buffered in
 *       order to look at its first twelve bytes, and every ceiling was enforced after the memory
 *       had already been spent.</li>
 *   <li><strong>Only the first bytes are read, through a stream.</strong> The header comes from
 *       {@link MultipartFile#getInputStream()} and stops at {@link #HEADER_BYTES}.</li>
 *   <li><strong>The accepted set is JPEG and PNG only.</strong> HEIC is refused server-side —
 *       gap row 11 says the client converts, and {@code javax.imageio} cannot decode it, so
 *       accepting it would mean storing a file this server can neither re-encode nor strip
 *       metadata from. PDF is refused because accepting it would mean shipping a page-render path
 *       and a whole second decoder's attack surface for a format no client sends today.</li>
 * </ol>
 *
 * <p>The declared content type is checked <em>and</em> the bytes are checked against it, in that
 * order. Either half alone is a hole: a permissive header smuggles anything past a type check, and
 * trusting the bytes alone would let a caller upload a JPEG and have it served back under a type
 * it never claimed.
 */
@Component
public class MultipartFileValidator {

    /** The JPEG media type — the only type this server ever stores or serves for a receipt. */
    public static final String IMAGE_JPEG = "image/jpeg";

    /** The PNG media type; accepted on the way in, never on the way out. */
    public static final String IMAGE_PNG = "image/png";

    /** Enough for every signature below; PNG's is the longest at eight. */
    static final int HEADER_BYTES = 12;

    /** JPEG and PNG. Nothing else: see the class javadoc for why HEIC and PDF are refused. */
    private static final Set<String> ACCEPTED_TYPES = Set.of(IMAGE_JPEG, IMAGE_PNG);

    private final ReceiptProperties properties;

    public MultipartFileValidator(ReceiptProperties properties) {
        this.properties = properties;
    }

    /**
     * Runs clauses (a) and (b) of the gauntlet and answers the type the bytes really are.
     *
     * @return {@link #IMAGE_JPEG} or {@link #IMAGE_PNG}, whichever the file's own bytes say
     * @throws UploadTooLargeException when the declared size exceeds this operation's own ceiling
     * @throws ValidationException     when the part is empty, its declared type is not accepted, or
     *                                 its bytes disagree with that type
     */
    public String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("A receipt file is required and must not be empty");
        }

        // (a) DECLARED size, before a byte of the body is read. The container's own multipart cap
        // is the backstop for a part that lies about its size; this is the cheap refusal.
        if (file.getSize() > properties.maxFileBytes()) {
            throw new UploadTooLargeException("Receipt file exceeds the "
                + properties.maxFileBytes() + " byte limit; the upload was "
                + file.getSize() + " bytes");
        }

        String declared = normalise(file.getContentType());
        if (!ACCEPTED_TYPES.contains(declared)) {
            throw new ValidationException(
                "Unsupported receipt file type. Accepted: JPEG and PNG. HEIC must be converted by "
                    + "the client and PDF is not accepted");
        }

        // (b) The bytes must be what the header claims. Read the header only.
        byte[] header = readHeader(file);
        String actual = sniff(header);
        if (actual == null || !actual.equals(declared)) {
            throw new ValidationException(
                "Receipt file content does not match its declared content type");
        }
        return actual;
    }

    private static String normalise(String contentType) {
        if (contentType == null) {
            return "";
        }
        int parameters = contentType.indexOf(';');
        String bare = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return bare.strip().toLowerCase(Locale.ROOT);
    }

    private static byte[] readHeader(MultipartFile file) {
        byte[] header = new byte[HEADER_BYTES];
        int read = 0;
        try (InputStream in = file.getInputStream()) {
            while (read < HEADER_BYTES) {
                int step = in.read(header, read, HEADER_BYTES - read);
                if (step < 0) {
                    break;
                }
                read += step;
            }
        } catch (IOException e) {
            throw new ValidationException("Could not read the uploaded receipt file", e);
        }
        if (read < 8) {
            throw new ValidationException(
                "Receipt file is too short to be an image of a supported type");
        }
        return header;
    }

    /**
     * The media type the first bytes really are, or null.
     *
     * <p>HEIC is deliberately absent from the answers rather than being detected and then refused
     * elsewhere: a type this server never stores has no reason to have a name inside it.
     */
    static String sniff(byte[] header) {
        if (isJpeg(header)) {
            return IMAGE_JPEG;
        }
        if (isPng(header)) {
            return IMAGE_PNG;
        }
        return null;
    }

    /** {@code FF D8 FF} — the SOI marker and the first byte of the first segment marker. */
    private static boolean isJpeg(byte[] h) {
        return h.length >= 3 && h[0] == (byte) 0xFF && h[1] == (byte) 0xD8 && h[2] == (byte) 0xFF;
    }

    /** {@code 89 50 4E 47 0D 0A 1A 0A} — the full eight-byte PNG signature, not a prefix of it. */
    private static boolean isPng(byte[] h) {
        return h.length >= 8
            && h[0] == (byte) 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47
            && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A;
    }
}
