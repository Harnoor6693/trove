package finance.trove.ai;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * Base of the small typed family every AI failure lands in
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11/§6).
 *
 * <p>Five shapes, and they are five because a client has to tell them apart: the feature being off
 * is not an error at all, a rejected key is a settings problem the member fixes, an unreachable
 * host is somebody else's outage, a timeout may be worth retrying, and a payload the member's own
 * privacy tier forbids is a decision rather than a fault. Collapsing them into one
 * "AI failed" 500 — which is what the source did — makes every one of those indistinguishable at
 * the point where the interface has to say something useful.
 *
 * <p>{@code GlobalExceptionHandler} needs no case for any of them: it reads {@link #getStatus()}
 * and {@link #getSlug()} off {@link TroveException}.
 *
 * <p><strong>What a message may contain.</strong> The host and the model, because the member
 * configured both and needs to see which one is wrong. Never the API key, never the prompt, and
 * never the endpoint's own response body — an OpenAI-compatible server routinely echoes the
 * request back inside an error, so passing its body through would put the household's data into a
 * log line and an HTTP response that were meant to be safe.
 */
public abstract class AiException extends TroveException {

    private static final long serialVersionUID = 1L;

    protected AiException(String message, HttpStatus status, String slug) {
        super(message, status, slug);
    }

    protected AiException(String message, HttpStatus status, String slug, Throwable cause) {
        super(message, status, slug, cause);
    }
}
