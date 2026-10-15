package finance.trove.ai;

import org.springframework.http.HttpStatus;

/**
 * The endpoint rejected the stored API key (401 or 403 upstream).
 *
 * <p>A 502 rather than a 401, and the distinction matters more than it looks. A 401 on a Trove
 * route means "your session is no good" and both clients respond to it by silently refreshing and
 * retrying, then signing the user out when that fails — which here would log a member out of their
 * finance server because a third-party API key expired. The caller's session is fine; a credential
 * they stored for somebody else's server is not.
 */
public class AiEndpointUnauthorizedException extends AiException {

    private static final long serialVersionUID = 1L;

    public AiEndpointUnauthorizedException(String message) {
        super(message, HttpStatus.BAD_GATEWAY, "ai-endpoint-unauthorized");
    }
}
