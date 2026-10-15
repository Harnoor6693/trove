package finance.trove.common.plugin;

/**
 * A {@link BankSource} could not reach its data source, or was refused by it.
 *
 * <p>Unchecked and deliberately independent of {@code finance.trove.common.exception}: a source
 * implementation says "the bank would not talk to me", and translating that into an HTTP status
 * is the orchestrator's job, not the source's. {@code SyncOrchestrator} catches it, records the
 * run as {@code FAILED} with the message, and publishes the failure outcome — a connection whose
 * credentials expired is exactly what a member needs to be told about, not something to swallow.
 */
public class BankSourceConnectionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BankSourceConnectionException(String message) {
        super(message);
    }

    public BankSourceConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
