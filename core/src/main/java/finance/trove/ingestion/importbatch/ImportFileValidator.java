package finance.trove.ingestion.importbatch;

import finance.trove.common.exception.ValidationException;
import finance.trove.common.plugin.SourceKind;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Decides whether an uploaded part may be read at all, and what format it is
 * ({@code docs/recovery/07-READINESS-GAPS.md} P2-11's magic-byte pattern, P2-12).
 *
 * <p>Three checks, in this order, and the order is the point:
 * <ol>
 *   <li><strong>Size, from the DECLARED part size</strong>, before a byte of the body is read.
 *       A file over the ceiling is refused without being buffered and without being stored.</li>
 *   <li><strong>The declared content type</strong>, against a list. A browser sends
 *       {@code application/vnd.ms-excel} for a {@code .csv} it thinks Excel owns and
 *       {@code application/octet-stream} for a {@code .ofx} it has never heard of, so the list is
 *       wider than it looks — which is exactly why it is not the only check.</li>
 *   <li><strong>The bytes themselves.</strong> The content type is a claim the caller makes; the
 *       first bytes are what the parser will actually see. A zip, gzip, bzip2, 7z or RAR wrapper is
 *       refused by name rather than being handed to a parser that would read its compressed bytes
 *       as text, and a file that is neither OFX nor CSV is refused before it is stored.</li>
 * </ol>
 *
 * <p>Nothing here quotes the file's content, its name, or any cell of it: a refusal says which
 * rule refused it.
 */
@Component
public class ImportFileValidator {

    /** How many leading bytes the format sniff reads. */
    static final int SNIFF_BYTES = 512;

    /**
     * Content types this endpoint accepts.
     *
     * <p>{@code application/octet-stream} is in the list because it is what a browser sends for an
     * extension it does not know, which is every {@code .ofx} and {@code .qfx} file. It is safe to
     * accept precisely because the byte sniff below is not optional.
     */
    static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of(
        "text/csv", "text/plain", "text/comma-separated-values", "application/csv",
        "application/vnd.ms-excel", "application/x-ofx", "application/ofx", "application/x-qfx",
        "application/qfx", "application/octet-stream");

    /** Archive signatures, refused by name. The parsers must never see compressed bytes. */
    private static final byte[][] ARCHIVE_SIGNATURES = {
        {0x50, 0x4B, 0x03, 0x04}, {0x50, 0x4B, 0x05, 0x06}, {0x50, 0x4B, 0x07, 0x08},
        {(byte) 0x1F, (byte) 0x8B}, {0x42, 0x5A, 0x68},
        {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C},
        {0x52, 0x61, 0x72, 0x21}, {(byte) 0xFD, 0x37, 0x7A, 0x58, 0x5A},
    };

    private final ImportLimits limits;

    public ImportFileValidator(ImportLimits limits) {
        this.limits = limits;
    }

    /**
     * The size check, run against the part's declared size before its body is read.
     *
     * @throws UploadTooLargeException 413 {@code upload-too-large}
     * @throws ValidationException     if the part is empty
     */
    public void requireAcceptableSize(long declaredSize) {
        if (declaredSize <= 0) {
            throw new ValidationException("The uploaded file is empty.");
        }
        if (declaredSize > limits.maxFileBytes()) {
            throw new UploadTooLargeException(declaredSize, limits.maxFileBytes());
        }
    }

    /**
     * Reads the whole part, refusing to read more than the ceiling allows.
     *
     * <p>The declared size is checked first, so an oversized upload is refused before a byte of it
     * is read. The bound is then applied to the read itself as well, because the declared size is
     * the caller's claim about the body and not the body — a part that says 1 KB and streams 2 GB
     * must not be able to fill the heap.
     */
    public byte[] readWithinLimit(InputStream body, long declaredSize) {
        requireAcceptableSize(declaredSize);
        byte[] content;
        try {
            content = body.readNBytes((int) Math.min(limits.maxFileBytes() + 1,
                Integer.MAX_VALUE));
        } catch (IOException e) {
            throw new ValidationException("The uploaded file could not be read.");
        }
        if (content.length > limits.maxFileBytes()) {
            throw new UploadTooLargeException(content.length, limits.maxFileBytes());
        }
        if (content.length == 0) {
            throw new ValidationException("The uploaded file is empty.");
        }
        return content;
    }

    /**
     * The content-type check.
     *
     * @throws ValidationException if the type is absent or not one this endpoint accepts
     */
    public void requireAcceptableContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new ValidationException("The uploaded file has no content type. Send it as "
                + "text/csv for a CSV export or application/x-ofx for an OFX or QFX file.");
        }
        String bare = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (!ACCEPTED_CONTENT_TYPES.contains(bare)) {
            throw new ValidationException("This endpoint does not accept " + bare + " uploads. "
                + "Send a CSV, OFX or QFX file exported from your bank.");
        }
    }

    /**
     * What the bytes actually are.
     *
     * @param declared what the caller said the file is, or {@code null} to let the bytes decide
     * @return {@link SourceKind#CSV} or {@link SourceKind#OFX}
     * @throws ValidationException if the bytes are an archive, are neither format, or are not the
     *                             format the caller declared
     */
    public SourceKind detectFormat(byte[] content, SourceKind declared) {
        refuseArchives(content);
        SourceKind sniffed = sniff(content);
        if (declared != null && declared != sniffed) {
            throw new ValidationException("This file was uploaded as " + declared + " but its "
                + "contents are " + sniffed + ". Upload it without a sourceKind and the server "
                + "will read it as what it is.");
        }
        return sniffed;
    }

    private static void refuseArchives(byte[] content) {
        for (byte[] signature : ARCHIVE_SIGNATURES) {
            if (startsWith(content, signature)) {
                throw new ValidationException("This looks like a compressed archive. Upload the "
                    + "CSV, OFX or QFX file itself rather than a zip or gzip of it.");
            }
        }
    }

    private static SourceKind sniff(byte[] content) {
        int length = Math.min(content.length, SNIFF_BYTES);
        String head = new String(content, 0, length, StandardCharsets.US_ASCII)
            .toLowerCase(Locale.ROOT);
        if (head.contains("ofxheader:") || head.contains("data:ofxsgml") || head.contains("<ofx>")
            || (head.contains("<?xml") && head.contains("ofx"))) {
            return SourceKind.OFX;
        }
        if (looksLikeText(content, length) && hasDelimitedFirstLine(head)) {
            return SourceKind.CSV;
        }
        throw new ValidationException("This file is neither an OFX/QFX document nor a delimited "
            + "text export. Upload the file your bank offers as CSV, OFX or QFX.");
    }

    /**
     * Text, not binary: no NUL byte, and no run of control characters.
     *
     * <p>A CSV cannot contain a NUL, and a binary file the browser mislabelled as
     * {@code application/octet-stream} almost always does within its first few hundred bytes. This
     * is what makes accepting {@code application/octet-stream} safe.
     */
    private static boolean looksLikeText(byte[] content, int length) {
        for (int i = 0; i < length; i++) {
            byte b = content[i];
            if (b == 0) {
                return false;
            }
            if (b > 0 && b < 0x09) {
                return false;
            }
            if (b > 0x0D && b < 0x20 && b != 0x1B) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasDelimitedFirstLine(String head) {
        String firstLine = head.split("\\R", 2)[0];
        for (char delimiter : new char[] {',', ';', '\t', '|'}) {
            if (firstLine.indexOf(delimiter) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
