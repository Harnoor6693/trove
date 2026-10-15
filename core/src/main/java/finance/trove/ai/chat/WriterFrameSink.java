package finance.trove.ai.chat;

import finance.trove.common.dto.ErrorResponse;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes §4.11's frames straight onto the response, byte for byte
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11, §9 risk 11).
 *
 * <h2>Why a writer and not an {@code SseEmitter}</h2>
 * Two reasons, and the second is the one that decides it.
 *
 * <p><strong>The frame format.</strong> {@code SseEmitter} builds the frames itself and writes
 * {@code data:} with no space after the colon; the pinned contract is {@code data: {…}} with one.
 * Both decode identically under the SSE grammar, which strips at most one leading space — but two
 * independently written clients are built against that contract and a server that emits something
 * other than what the document says is the drift this project exists to remove.
 *
 * <p><strong>The security chain.</strong> An {@code SseEmitter} — or any
 * {@code ResponseBodyEmitter}, {@code StreamingResponseBody} or {@code DeferredResult} — puts the
 * request into Servlet async mode, and completing it triggers an ASYNC dispatch back through the
 * filter chain. Boot registers Spring Security for {@code ASYNC} dispatches, the chain is
 * {@code STATELESS} so there is no {@code SecurityContextRepository} to reload a principal from,
 * and {@code CookieOrBearerJwtAuthFilter} is a {@code OncePerRequestFilter} that does not re-run on
 * an async dispatch. The dispatch therefore arrives anonymous, {@code AuthorizationFilter} refuses
 * it, and the client sees the response truncated mid-chunk — which is exactly what the first
 * version of {@code ChatStreamIT} caught. Writing the body from the request thread has no async
 * dispatch to be refused.
 *
 * <p>The cost is one request thread held for the length of one member's generation, bounded by
 * that member's own read timeout (capped at 120 s by {@code AiTimeouts}) and by the
 * {@code /api/v1/chat/**} rate-limit row V12 already seeds. Making this asynchronous again needs
 * either {@code spring.security.filter.dispatcher-types} or a security-context repository that
 * survives the dispatch — both are {@code SecurityConfig} / {@code application.yml} decisions, and
 * neither is this step's to make.
 *
 * <h2>Every frame is flushed</h2>
 * {@code flush()} after each frame pushes it through Tomcat to the socket, and
 * {@code AuditLogFilter} does not wrap the response in a {@code ContentCachingResponseWrapper}
 * (collapse step 4 removed it — that wrapper buffers the whole body and releases it in its
 * {@code finally}, so nothing would reach the client until the generation finished and the coach
 * would appear to hang for its whole length). §9 risk 11 is the test that holds both halves.
 *
 * <h2>Writes are best-effort</h2>
 * A member who closed the tab cannot be written to; that is not a failure of the answer, which is
 * persisted regardless, and it must not propagate into the AI client's callback where it would
 * abort a generation nobody is waiting for. {@link PrintWriter} never throws — it latches an error
 * flag — so a broken client simply stops the sink.
 */
public class WriterFrameSink implements CoachFrameSink {

    /** The terminator §4.11 pins. Not a named SSE event: both clients read the literal payload. */
    public static final String DONE_PAYLOAD = "[DONE]";

    private static final Logger LOG = LoggerFactory.getLogger(WriterFrameSink.class);

    private final PrintWriter writer;
    private final ObjectMapper json;
    private final String instance;
    private final String requestId;
    private boolean broken;

    /**
     * @param instance  the matched route TEMPLATE from {@code RequestPath.describe}, never the raw
     *                  URI
     * @param requestId the correlation id already on the {@code X-Request-Id} response header
     */
    public WriterFrameSink(PrintWriter writer, ObjectMapper json, String instance,
                           String requestId) {
        this.writer = writer;
        this.json = json;
        this.instance = instance;
        this.requestId = requestId;
    }

    /**
     * {@code data: {"choices":[{"delta":{"content":"…"}}]}} — the OpenAI-compatible shape both
     * clients' readers already parse. The object is serialised rather than concatenated so that a
     * quote, a newline or a backslash in the model's text cannot break the frame.
     */
    @Override
    public void delta(String content) {
        write(json.writeValueAsString(
            Map.of("choices", List.of(Map.of("delta", Map.of("content", content))))));
    }

    @Override
    public void done() {
        write(DONE_PAYLOAD);
    }

    /**
     * {@code data: {"error":{…}}} carrying the same RFC 7807 shape a non-streaming failure would
     * have carried, so a client has one error vocabulary either way.
     *
     * <p>{@code detail} is a fixed member-facing sentence chosen by slug in
     * {@code CoachChatService}, never an exception's own message. That is the difference between
     * "we currently do not put anything sensitive in these messages" and "nothing sensitive can
     * end up here": no stack trace, no prompt, no endpoint host and no upstream response body can
     * reach a frame, whatever a future exception decides to say.
     */
    @Override
    public void failure(int status, String slug, String detail) {
        ErrorResponse error = ErrorResponse.builder(status)
            .type(slug)
            .title(HttpStatus.valueOf(status).getReasonPhrase())
            .detail(detail)
            .instance(instance)
            .requestId(requestId)
            .build();
        write(json.writeValueAsString(Map.of("error", error)));
    }

    private void write(String payload) {
        if (broken) {
            return;
        }
        writer.write("data: " + payload + "\n\n");
        writer.flush();
        if (writer.checkError()) {
            // swallow-ok: PrintWriter latches rather than throws, and a client that has gone is
            // not a failure of the answer — the service persists it either way. Nothing more is
            // written on this response.
            broken = true;
            LOG.debug("Coach stream client went away before the answer finished");
        }
    }
}
