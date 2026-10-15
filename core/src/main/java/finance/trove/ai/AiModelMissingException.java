package finance.trove.ai;

import org.springframework.http.HttpStatus;

/**
 * The endpoint answered, but does not serve the model the caller's settings name.
 *
 * <p>A 502 in the same family as the other endpoint faults, because the member fixes it the same
 * way: on the settings screen, against the real model list {@code GET /v1/ai/models} returns. The
 * message names the model that was asked for, which is the caller's own configured value and
 * therefore theirs to see.
 */
public class AiModelMissingException extends AiException {

    private static final long serialVersionUID = 1L;

    public AiModelMissingException(String message) {
        super(message, HttpStatus.BAD_GATEWAY, "ai-model-missing");
    }
}
