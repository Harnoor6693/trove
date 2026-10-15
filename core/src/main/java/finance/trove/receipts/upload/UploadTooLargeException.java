package finance.trove.receipts.upload;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * The upload cannot be stored because it exceeds a ceiling
 * ({@code docs/recovery/07-READINESS-GAPS.md} row 11; §4.10).
 *
 * <p>413 with the existing {@code upload-too-large} slug — the same one
 * {@code MaxUploadSizeExceededException} maps to in {@link
 * finance.trove.common.exception.GlobalExceptionHandler}, and the same one
 * {@code POST /v1/imports} answers, because from a client's point of view there is one refusal
 * whichever ceiling was hit. No new slug is invented for this.
 *
 * <p><strong>The message names bytes only.</strong> Never a filename, never a storage key, never a
 * path: a 413 that echoes what the client called its file is a reflected-content hole in an error
 * body, and one that names a key tells a caller something about a store it cannot address anyway.
 */
public class UploadTooLargeException extends TroveException {

    private static final long serialVersionUID = 1L;

    public UploadTooLargeException(String message) {
        super(message, HttpStatus.CONTENT_TOO_LARGE, "upload-too-large");
    }
}
