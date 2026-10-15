package finance.trove.ai;

import finance.trove.ai.client.AiStreamListener;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.ai.dto.AiModel;
import java.util.List;

/**
 * One member's AI, as steps 13, 14 and 17 see it. Obtained from {@link AiGateway#forUser}.
 *
 * <p>Short-lived by design: hold it for the length of one operation and get a new one next time.
 * The settings it was built from are a snapshot, but the egress policy — the part that decides
 * whether the destination may be contacted at all, and pins the address that will be — runs again
 * inside every one of the calls below. Nothing here caches a connection, a resolved address or a
 * decrypted key between calls.
 *
 * <p>Every network method throws a subclass of {@link AiException} and nothing else:
 * {@link AiDisabledException} when the member has no AI, {@link AiPolicyBlockedException} when
 * their own privacy tier forbids the payload, and the endpoint failures
 * ({@link AiUnreachableException}, {@link AiEndpointUnauthorizedException},
 * {@link AiModelMissingException}, {@link AiTimeoutException}). An
 * {@code OutboundUrlPolicyException} can also surface, as a 400, when a URL that was legal when it
 * was saved no longer is.
 */
public interface AiHandle {

    /**
     * Whether this member has AI switched on with an endpoint and a model behind it.
     *
     * <p>The cheap check every caller should make before building an expensive prompt. It opens no
     * connection and reads nothing but the snapshot.
     */
    boolean isEnabled();

    /** How far this member lets their data travel. Never null, even when AI is off. */
    PrivacyTier privacyTier();

    /** The configured chat model id, or null when none is set. */
    String chatModel();

    /** The configured vision model id, or null — receipts fall back to manual entry without one. */
    String visionModel();

    /** A non-streaming completion. */
    AiCompletion chat(List<AiMessage> messages, AiSensitivity sensitivity);

    /**
     * A streaming completion: deltas reach {@code listener} as they arrive, and the assembled
     * answer comes back for persisting and auditing.
     */
    AiCompletion chatStream(List<AiMessage> messages, AiSensitivity sensitivity,
                            AiStreamListener listener);

    /**
     * One image plus one prompt, against the configured vision model.
     *
     * @param contentType the image's media type, e.g. {@code image/jpeg}; it becomes the media type
     *                    of the {@code data:} URI the endpoint receives
     */
    AiCompletion vision(String prompt, byte[] image, String contentType, AiSensitivity sensitivity);

    /**
     * The models this member's endpoint serves. {@link AiSensitivity#ORDINARY} by construction —
     * it sends nothing but a bearer token — so it is allowed at either privacy tier.
     */
    List<AiModel> models();
}
