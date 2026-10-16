package finance.trove.common.net;

import finance.trove.common.exception.TroveException;
import org.springframework.http.HttpStatus;

/**
 * A URL a user supplied — an AI endpoint, a webhook target, a SimpleFIN access URL — was refused
 * by {@link OutboundUrlPolicy}.
 *
 * <p>A 400, because the caller can fix it by supplying a different URL. A refusal for the URL's
 * shape (scheme, credentials, port) names the rule. A refusal by address is one generic sentence,
 * the same for loopback, private, link-local, metadata and a name that does not resolve: naming
 * the range, or the IP, would turn the validator into the internal network probe it exists to
 * prevent (B3-10). The specific reason is logged server-side.
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
