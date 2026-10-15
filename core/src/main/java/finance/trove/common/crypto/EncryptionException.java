package finance.trove.common.crypto;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * A field could not be encrypted or decrypted.
 *
 * <p>A 500, and the message never carries the offending bytes: a failed decryption means either
 * ciphertext tampering or a key rotated without re-encrypting, and both are operator problems,
 * not caller problems. {@code GlobalExceptionHandler} logs the cause against the request id.
 */
public class EncryptionException extends TroveException {

    private static final long serialVersionUID = 1L;

    public EncryptionException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, "encryption-failed");
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, "encryption-failed", cause);
    }
}
