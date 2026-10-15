package finance.trove.ai.chat;

/**
 * Where one streamed answer's frames go ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11's pinned
 * wire contract).
 *
 * <p>An interface rather than a bare writer, for two reasons. The first is that the contract is
 * three <em>kinds</em> of frame — a delta, the terminator, an in-band error — and naming them here
 * is what keeps the formatting in one implementation ({@link WriterFrameSink}) instead of scattered
 * through a service. The second is §9 risk 11: {@code ChatStreamIT} has to observe that five
 * upstream chunks reach the client as five separate frames rather than one buffered blob, and a
 * sink it can record against is how that is asserted at the boundary the service actually writes
 * to.
 *
 * <p>Every method is best-effort. A client that has closed the connection cannot be written to, and
 * that is not a failure of the answer — the answer is persisted by the service either way. An
 * implementation therefore swallows a write failure rather than propagating it into the AI client's
 * callback, where it would abort a generation the member is no longer waiting for anyway.
 */
public interface CoachFrameSink {

    /**
     * One content delta, forwarded the moment it arrives.
     *
     * @param content already control-character-stripped; never null, never empty
     */
    void delta(String content);

    /** The answer is complete: the {@code data: [DONE]} terminator, and nothing after it. */
    void done();

    /**
     * The stream failed after the response was already open. Emits the in-band
     * {@code data: {"error":…}} frame and nothing after it — no {@code [DONE]}, because the answer
     * did not finish.
     *
     * <p>The three pieces the caller knows are passed rather than a whole {@code ErrorResponse},
     * because the other three — the matched route template, the request id and the title — belong
     * to the request, and the implementation is the thing that has one. {@code detail} is an
     * {@code AiException}'s own message, which is written for a member to read: never a stack
     * trace, never the prompt, never the endpoint's host and never its response body.
     *
     * @param status the HTTP status the same failure would have carried had it not been streamed
     * @param slug   the error slug, from the enum {@code openapi/trove-core.yaml} declares
     * @param detail one sentence for the member
     */
    void failure(int status, String slug, String detail);
}
