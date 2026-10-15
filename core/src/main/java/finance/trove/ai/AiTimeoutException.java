package finance.trove.ai;

import org.springframework.http.HttpStatus;

/**
 * The endpoint did not answer inside the deadline.
 *
 * <p>A 504, separated from {@link AiUnreachableException} because the two want different
 * behaviour: a connection refused now will be refused again in a second, and a model that has not
 * finished generating may well finish on a second attempt with a longer deadline. The deadline is
 * the caller's own {@code readTimeoutMs}, capped by the install's {@code TROVE_HTTP_*} limits, so
 * the fix is often a settings change rather than a retry.
 */
public class AiTimeoutException extends AiException {

    private static final long serialVersionUID = 1L;

    /**
     * The transport exception is deliberately NOT carried as a cause anywhere in this package: its
     * message is Spring's {@code I/O error on GET request for "<full uri>"}, and an AI endpoint's
     * path or query routinely carries the member's access token. {@code GlobalExceptionHandler}
     * logs a 5xx with the whole throwable, so a cause here would put that URL in the log.
     */
    public AiTimeoutException(String message) {
        super(message, HttpStatus.GATEWAY_TIMEOUT, "ai-timeout");
    }
}
