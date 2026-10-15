package finance.trove.ai;

import finance.trove.ai.client.AiStreamListener;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import java.util.List;

/**
 * The one way to drive {@link AiHandle#chatStream} from outside {@code finance.trove.ai}
 * (collapse step 13).
 *
 * <p>It exists for a rule rather than for a feature. {@code ArchitectureTest}'s
 * {@code onlyTheGatewayReachesTheAiClient} forbids any class outside {@code finance.trove.ai} and
 * {@code finance.trove.ai.client} from depending on {@code finance.trove.ai.client..}, and
 * {@link AiStreamListener} — the callback {@code chatStream} takes — lives in that client package.
 * A coach controller in {@code finance.trove.ai.chat} implementing it directly would trip the
 * rule, and widening the rule to {@code finance.trove.ai..} would also let a later step reach
 * {@code OpenAiCompatibleClient} itself, which is exactly what §9 risk 10 forbids. So the listener
 * is implemented here, once, where the rule already allows it, and callers hand in a {@link Sink}.
 *
 * <p>This adds no behaviour of its own: it forwards the three callbacks unchanged, in order, on
 * the calling thread, and returns what the handle returned. The client still calls
 * {@code onError} <em>and</em> throws, which is what lets a controller emit an in-band error frame
 * to a response it has already begun writing while the caller still learns that the call failed.
 */
public final class AiStreamCall {

    private AiStreamCall() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** What a caller outside this package receives a streaming completion on. */
    public interface Sink {

        /**
         * One content delta, exactly as {@link AiStreamListener#onDelta} produced it: never null,
         * never empty.
         */
        void onDelta(String content);

        /** The upstream sent its terminator; the answer is complete. */
        void onDone();

        /** The stream failed. The same exception is thrown to the caller straight afterwards. */
        void onError(AiException error);
    }

    /**
     * Streams a completion, forwarding every frame to {@code sink} as it arrives.
     *
     * @return the assembled answer, for persisting; the exceptions are {@link AiHandle}'s
     */
    public static AiCompletion stream(AiHandle handle, List<AiMessage> messages,
                                      AiSensitivity sensitivity, Sink sink) {
        return handle.chatStream(messages, sensitivity, new AiStreamListener() {
            @Override
            public void onDelta(String content) {
                sink.onDelta(content);
            }

            @Override
            public void onDone() {
                sink.onDone();
            }

            @Override
            public void onError(AiException error) {
                sink.onError(error);
            }
        });
    }
}
