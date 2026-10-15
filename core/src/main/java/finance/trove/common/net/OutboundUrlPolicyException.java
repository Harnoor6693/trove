package finance.trove.common.net;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * A URL a user supplied — an AI endpoint, a webhook target, a SimpleFIN access URL — was refused
 * by {@link OutboundUrlPolicy}.
 *
 * <p>A 400, because the caller can fix it by supplying a different URL, and the message says
 * exactly why so that Settings › AI can show something better than "invalid". The message names
 * the rule ("resolves to a private address") but never the resolved address itself: echoing the
 * IP back would turn the validator into the internal port scanner it exists to prevent.
 */
public class OutboundUrlPolicyException extends TroveException {

    private static final long serialVersionUID = 1L;

    public OutboundUrlPolicyException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "outbound-url-rejected");
    }

    public OutboundUrlPolicyException(String message, Throwable cause) {
        super(message, HttpStatus.BAD_REQUEST, "outbound-url-rejected", cause);
    }
}
