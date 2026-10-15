package finance.trove.notifications.send;

/**
 * What a {@link DeliveryChannel} actually did — the return type that makes finding D3 impossible
 * to write again ({@code docs/recovery/01-FINDINGS.md} D3; §2.8).
 *
 * <h2>The defect this type exists to prevent</h2>
 * The pre-collapse {@code NotificationDispatcher} (lines 87-109) set {@code status = "SENT"} before
 * it attempted anything and only downgraded inside a {@code catch}. None of its three channels
 * ever threw: {@code EmailChannel} caught {@code MailException} and logged,
 * {@code ApnsChannel} and {@code FcmChannel} did the same, and the dispatcher's own
 * {@code default} branch for an unknown channel logged too. So {@code notification_log.status} was
 * permanently {@code SENT}, {@code error_message} permanently null, and both {@code FAILED} and
 * the schema's own {@code PENDING} were unreachable code. Every install's delivery log said
 * everything had been delivered, whatever had happened.
 *
 * <p>An exception-based contract could not have fixed it either, because "the SMTP server refused
 * this address" is an ordinary outcome rather than a programming error, and a channel author who
 * catches it is behaving reasonably. The fix is that a channel RETURNS what happened, and that the
 * type refuses to describe a failure without a reason: a {@code FAILED} with a null error is
 * exactly the row D3 produced, and this record will not construct one. V32 carries the same rule
 * as a CHECK constraint, so neither layer relies on the other.
 *
 * @param status what happened
 * @param error  why it failed; {@code null} on success, non-blank on failure
 */
public record DeliveryResult(Status status, String error) {

    /** Whether a channel delivered. There is no third value: {@code PENDING} is the log's, not a
     * channel's — a channel that has not finished has not returned. */
    public enum Status {
        /** The channel handed the message off successfully. */
        SENT,
        /** The channel could not; {@link DeliveryResult#error()} says why. */
        FAILED
    }

    public DeliveryResult {
        if (status == null) {
            throw new IllegalArgumentException("a delivery result must say what happened");
        }
        if (status == Status.FAILED && (error == null || error.isBlank())) {
            throw new IllegalArgumentException(
                "a FAILED delivery must carry the reason it failed — a status that says FAILED "
                    + "with no error is the D3 defect written the other way round");
        }
        if (status == Status.SENT && error != null) {
            throw new IllegalArgumentException(
                "a SENT delivery carries no error, got: " + error);
        }
    }

    /** Delivered. */
    public static DeliveryResult sent() {
        return new DeliveryResult(Status.SENT, null);
    }

    /**
     * Not delivered, for this reason.
     *
     * @param reason a sentence an operator can act on; never null or blank
     */
    public static DeliveryResult failed(String reason) {
        return new DeliveryResult(Status.FAILED, reason);
    }

    /**
     * Not delivered, because of {@code cause}.
     *
     * <p>Uses the exception's message when it has one and its class name when it does not — a
     * {@code NullPointerException} with a null message would otherwise produce a FAILED row with
     * no reason, which this type refuses to construct.
     */
    public static DeliveryResult failed(Throwable cause) {
        String message = cause == null ? null : cause.getMessage();
        return failed(message == null || message.isBlank()
            ? (cause == null ? "unknown failure" : cause.getClass().getSimpleName())
            : message);
    }

    public boolean isSent() {
        return status == Status.SENT;
    }
}
