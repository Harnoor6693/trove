package finance.trove.ai.client;

import finance.trove.ai.AiException;

/**
 * How a caller receives a streaming completion frame by frame.
 *
 * <p>It exists so that collapse step 13's SSE controller can forward what the endpoint sends as it
 * arrives, rather than buffering a whole answer and emitting it at the end — which is what the
 * source did, and is why the coach appeared to hang for the length of a generation. The three
 * callbacks correspond exactly to the three frame kinds the pinned wire contract of §4.11 defines:
 * a delta, the {@code [DONE]} sentinel, and an in-band error.
 *
 * <p>Called on the calling thread, in order, while the upstream response is still open. An
 * implementation that blocks here holds the socket open, so the SSE controller writes and flushes
 * and does nothing else.
 */
public interface AiStreamListener {

    /**
     * One content delta, already extracted from {@code choices[0].delta.content}. Never null and
     * never empty — a frame that carries no content (a role-only opener, a keep-alive comment) is
     * not surfaced, because a client forwarding it would emit an empty SSE frame for nothing.
     */
    void onDelta(String content);

    /**
     * The upstream sent {@code data: [DONE]}. Called at most once, and only on a stream that
     * completed; a stream that ends without the sentinel is a truncation and goes to
     * {@link #onError} instead.
     */
    void onDone();

    /**
     * The stream failed: an in-band {@code data: {"error":...}} frame, a transport failure, or a
     * truncated stream.
     *
     * <p>The client calls this <em>and then throws the same exception</em>. Both, deliberately:
     * the callback is how a controller that has already begun writing its own response emits an
     * in-band error frame to its client, and the throw is how the calling service — which has an
     * audit row to write and a metric to record — learns that the call failed.
     */
    void onError(AiException error);
}
