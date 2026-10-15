package finance.trove.ai;

import org.springframework.http.HttpStatus;

/**
 * The caller's own AI endpoint refused the connection, failed the TLS handshake, or answered
 * something this client cannot use.
 *
 * <p>A 502: the fault is upstream of Trove and downstream of the caller's request, which is what
 * Bad Gateway means. Deliberately not a 500 — a 500 says Trove broke, sends the member looking at
 * the wrong logs, and would be indistinguishable from a genuine bug in this code.
 */
public class AiUnreachableException extends AiException {

    private static final long serialVersionUID = 1L;

    public AiUnreachableException(String message) {
        super(message, HttpStatus.BAD_GATEWAY, "ai-unreachable");
    }

    public AiUnreachableException(String message, Throwable cause) {
        super(message, HttpStatus.BAD_GATEWAY, "ai-unreachable", cause);
    }
}
