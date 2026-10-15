package finance.trove.common.storage;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * A read, write or delete against the {@code trove_data} volume failed, or a caller supplied a
 * key that does not address a file inside it.
 *
 * <p>A 500: the caller never chooses a storage key — keys are generated server-side by
 * {@link LocalDiskStorage} — so a bad key is an internal bug or an attempted traversal, and
 * neither is something the caller can fix by changing their request.
 */
public class StorageException extends TroveException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, "storage-failure");
    }

    public StorageException(String message, Throwable cause) {
        super(message, HttpStatus.INTERNAL_SERVER_ERROR, "storage-failure", cause);
    }
}
