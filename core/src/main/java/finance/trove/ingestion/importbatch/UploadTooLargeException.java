package finance.trove.ingestion.importbatch;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * An upload over {@link ImportLimits#maxFileBytes()}.
 *
 * <p>413 with the {@code upload-too-large} slug — the same answer the container's own
 * {@code MaxUploadSizeExceededException} produces, because from a client's point of view the two
 * are one refusal with two ceilings behind it. It is raised from the part's DECLARED size, before
 * a byte of the body is read, so an oversized file is never buffered and never stored.
 *
 * <p>The message names the ceiling and the size, never the filename or any content.
 */
public class UploadTooLargeException extends TroveException {

    private static final long serialVersionUID = 1L;

    public UploadTooLargeException(long sizeBytes, long maxBytes) {
        super("The uploaded file is " + sizeBytes + " bytes, and this server accepts at most "
            + maxBytes + ".", HttpStatus.CONTENT_TOO_LARGE, "upload-too-large");
    }

    private UploadTooLargeException(String message) {
        super(message, HttpStatus.CONTENT_TOO_LARGE, "upload-too-large");
    }

    /**
     * The household's stored uploads would go past
     * {@link ImportLimits#householdUploadQuotaBytes()} (confirmed review finding 3).
     *
     * <p>The same slug and the same status as the per-file ceiling, deliberately: from a client's
     * point of view there is one refusal — this upload cannot be stored — and the message says
     * which of the two ceilings it hit. It names bytes and nothing else: no filename, no key, no
     * path.
     */
    static UploadTooLargeException quotaExceeded(long usedBytes, long incomingBytes,
                                                 long quotaBytes) {
        return new UploadTooLargeException("This household already holds " + usedBytes
            + " bytes of uploaded import files and this server keeps at most " + quotaBytes
            + ", so a further " + incomingBytes + " bytes cannot be stored. Commit or undo an "
            + "earlier import, or wait for an abandoned preview to be swept.");
    }
}
