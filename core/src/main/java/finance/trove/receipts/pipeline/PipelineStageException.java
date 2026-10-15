package finance.trove.receipts.pipeline;

/**
 * A pipeline stage could not do its work ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6 — the
 * pipeline package is KEPT, and this is its error contract).
 *
 * <p>{@link #isFatal()} is what the orchestrator branches on:
 * <ul>
 *   <li><strong>fatal</strong> — the run stops, the receipt becomes {@code FAILED} and
 *       {@link #getUserMessage()} is persisted as its {@code errorMessage};</li>
 *   <li><strong>not fatal</strong> — the failure is logged and the next stage runs, keeping
 *       whatever earlier stages already wrote.</li>
 * </ul>
 *
 * <p>{@link #getUserMessage()} is shown to the member, so it is one sentence and never a stack
 * trace, a path, a storage key or the address of anything.
 */
public class PipelineStageException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String stageName;
    private final String userMessage;
    private final boolean fatal;

    public PipelineStageException(String stageName, String userMessage, boolean fatal) {
        this(stageName, userMessage, fatal, null);
    }

    public PipelineStageException(String stageName, String userMessage, boolean fatal,
                                  Throwable cause) {
        super("[" + stageName + "] " + userMessage, cause);
        this.stageName = stageName;
        this.userMessage = userMessage;
        this.fatal = fatal;
    }

    public String getStageName() {
        return stageName;
    }

    /** One sentence, safe to persist and show. */
    public String getUserMessage() {
        return userMessage;
    }

    public boolean isFatal() {
        return fatal;
    }
}
