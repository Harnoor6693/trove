package finance.trove.ai;

import org.springframework.http.HttpStatus;

/**
 * The caller has configured no AI endpoint, or has turned theirs off.
 *
 * <p>A 409 with slug {@code ai-disabled}, and it is the one AI failure that is not a failure: AI
 * is off by default on every install and nothing in Trove requires it. Steps 13, 14 and 17 catch
 * this specifically and fall back — the coach offers the settings screen, receipts take the manual
 * path, categorisation uses rules alone — rather than surfacing an error.
 *
 * <p>Not a 404: the settings resource exists and the caller can read it. Not a 403: nobody is
 * refusing them anything. 409 says "the state of your configuration does not allow this", which is
 * exactly what happened and is already how {@code totp-not-enrolled} reads.
 */
public class AiDisabledException extends AiException {

    private static final long serialVersionUID = 1L;

    public AiDisabledException(String message) {
        super(message, HttpStatus.CONFLICT, "ai-disabled");
    }
}
