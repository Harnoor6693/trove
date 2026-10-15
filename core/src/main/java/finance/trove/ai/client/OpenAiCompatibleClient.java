package finance.trove.ai.client;

import finance.trove.ai.AiEndpointUnauthorizedException;
import finance.trove.ai.AiException;
import finance.trove.ai.AiModelMissingException;
import finance.trove.ai.AiTimeoutException;
import finance.trove.ai.AiUnreachableException;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.ai.dto.AiModel;
import finance.trove.app.HttpClientConfig;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.apache.hc.core5.http.io.EofSensorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The one class in the monolith that opens a connection to an AI endpoint
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §4.11).
 *
 * <p>It replaces {@code libs/ai-gateway-client}'s {@code LiteLlmRestClient}, {@code AiGatewayClient}
 * and {@code ModelRoutingService}, model-manager's {@code LiteLlmChatGateway}, and the direct
 * {@code RestClient} call {@code ChatService} made to Ollama behind all of them. Four ways to reach
 * a model, three of which had no timeout and none of which validated the destination. There is one
 * now, and it is OpenAI-compatible because that is the interface Ollama, llama.cpp, LM Studio,
 * vLLM, OpenAI and OpenRouter all speak — a router across providers buys nothing when every
 * provider speaks the same protocol and the endpoint is the member's own choice anyway.
 *
 * <p><strong>Every call is pinned.</strong> The client is built through
 * {@link HttpClientConfig.PinnedRestClientFactory} from a {@link AiEndpoint#target()} that
 * {@code OutboundUrlPolicy} approved moments earlier, so the socket opens to the address that was
 * validated and to no other. Redirects are off, so a 302 cannot walk the connection to a host no
 * policy ever saw.
 *
 * <p><strong>What never leaves this class.</strong> The API key, the prompt, the completion, the
 * image, and the endpoint's own response body. Log lines carry the host, the operation, the status
 * and the elapsed time; exception messages carry the host and, where the member configured it, the
 * model. An OpenAI-compatible server routinely quotes the request back inside an error body, so
 * passing that body into a message or a log would put the household's data into exactly the two
 * places this design is trying to keep it out of.
 */
@Component
public class OpenAiCompatibleClient {

    /**
     * The ceiling on any single response, streaming or not (§5.5's 5 MB cap). A model that streams
     * 5 MB of text has run away; without a cap, a hostile or broken endpoint can hold a request
     * thread and grow the heap until the JVM dies.
     *
     * <p>Counted in <strong>bytes taken off the socket</strong>, on both paths, and checked after
     * every read rather than at a frame boundary. A cap that is only evaluated once a line is
     * complete is not a cap: one {@code data:} line with no newline in it grows the reader's own
     * buffer without limit, and this process is the whole install — every household's requests are
     * served by this JVM.
     */
    static final long MAX_RESPONSE_BYTES = 5L * 1024 * 1024;

    /** How much is taken off the socket at a time on both read paths. */
    private static final int READ_CHUNK_BYTES = 8 * 1024;

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    private static final String CHAT_PATH = "/chat/completions";
    private static final String MODELS_PATH = "/models";
    private static final String DATA_PREFIX = "data:";
    private static final String DONE_SENTINEL = "[DONE]";

    private final HttpClientConfig.PinnedRestClientFactory clients;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleClient(HttpClientConfig.PinnedRestClientFactory clients,
                                  ObjectMapper objectMapper) {
        this.clients = clients;
        this.objectMapper = objectMapper;
    }

    /** A non-streaming completion. */
    public AiCompletion chat(AiEndpoint endpoint, List<AiMessage> messages) {
        Map<String, Object> payload = chatPayload(endpoint.model(), contentMessages(messages),
            false);
        JsonNode body = postForJson(endpoint, CHAT_PATH, payload, "chat");
        return completionFrom(body, endpoint.model());
    }

    /**
     * A streaming completion. Deltas reach {@code listener} as they arrive; the assembled answer
     * is returned so the caller can audit and persist it.
     *
     * <p>A stream that ends without {@code data: [DONE]} is treated as a truncation rather than as
     * a completion. Presenting half an answer as if it were whole is the worse failure: the member
     * has no way to tell, and a coach reply cut off mid-sentence reads as advice.
     */
    public AiCompletion chatStream(AiEndpoint endpoint, List<AiMessage> messages,
                                   AiStreamListener listener) {
        Map<String, Object> payload = chatPayload(endpoint.model(), contentMessages(messages),
            true);
        long started = System.nanoTime();
        long deadline = deadlineFrom(started, endpoint);
        try {
            AiCompletion completion = clientFor(endpoint).post()
                .uri(CHAT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> authorise(headers, endpoint))
                .body(payload)
                .exchange((request, response) -> readStream(endpoint, response, listener,
                    deadline));
            logCall(endpoint, "chatStream", 200, started);
            return completion;
        } catch (AiException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw streamFailure(endpoint, listener, transportFailure(endpoint, e));
        } catch (RuntimeException e) {
            LOG.warn("AI chatStream to {} failed inside the client: {}", endpoint.host(),
                e.getClass().getSimpleName());
            throw streamFailure(endpoint, listener,
                new AiUnreachableException("The AI endpoint " + endpoint.host()
                    + " did not answer with a usable stream"));
        }
    }

    /**
     * A vision completion: one image plus one prompt, in the content-parts shape every
     * OpenAI-compatible vision model expects.
     *
     * <p>The image travels as a {@code data:} URI in {@code image_url}, not as a URL the endpoint
     * would fetch. A URL would mean the endpoint reaching back into whatever host served it, which
     * for a locally stored receipt means either exposing the object store or handing an arbitrary
     * third party a URL to fetch — neither of which the egress policy governs.
     */
    public AiCompletion vision(AiEndpoint endpoint, String prompt, byte[] image,
                               String contentType) {
        if (endpoint.visionModel() == null || endpoint.visionModel().isBlank()) {
            throw new AiModelMissingException("No vision model is configured for " + endpoint.host()
                + ", so images cannot be read");
        }
        Map<String, Object> imageUrl = new LinkedHashMap<>();
        imageUrl.put("url", "data:" + contentType + ";base64,"
            + Base64.getEncoder().encodeToString(image));

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put("type", "image_url");
        imagePart.put("image_url", imageUrl);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", List.of(textPart, imagePart));

        Map<String, Object> payload = chatPayload(endpoint.visionModel(), List.of(message), false);
        JsonNode body = postForJson(endpoint, CHAT_PATH, payload, "vision");
        return completionFrom(body, endpoint.visionModel());
    }

    /**
     * The models the endpoint serves. Tolerates both shapes in the wild: {@code {"data":[...]}} as
     * OpenAI documents it, and a bare array, which several self-hosted servers return.
     */
    public List<AiModel> models(AiEndpoint endpoint) {
        long started = System.nanoTime();
        long deadline = deadlineFrom(started, endpoint);
        JsonNode body = execute(endpoint, "models", started, () -> clientFor(endpoint).get()
            .uri(MODELS_PATH)
            .accept(MediaType.APPLICATION_JSON)
            .headers(headers -> authorise(headers, endpoint))
            .exchange((request, response) -> readJson(endpoint, response, deadline)));

        JsonNode array = body.isArray() ? body : body.path("data");
        List<AiModel> models = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode node : array) {
                String id = text(node, "id");
                if (id != null && !id.isBlank()) {
                    models.add(new AiModel(id, text(node, "owned_by"), longOrNull(node, "created")));
                }
            }
        }
        return List.copyOf(models);
    }

    // ------------------------------------------------------------------ internals

    private RestClient clientFor(AiEndpoint endpoint) {
        return clients.builderFor(endpoint.target(), endpoint.connectTimeout(),
            endpoint.readTimeout()).build();
    }

    private static void authorise(HttpHeaders headers, AiEndpoint endpoint) {
        if (endpoint.apiKey() != null && !endpoint.apiKey().isBlank()) {
            headers.setBearerAuth(endpoint.apiKey());
        }
    }

    private static Map<String, Object> chatPayload(String model, List<Object> messages,
                                                   boolean stream) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("stream", stream);
        return payload;
    }

    /**
     * Built as maps rather than as records because the wire keys are snake_case
     * ({@code image_url}, {@code owned_by}) and this application's {@code ObjectMapper} applies a
     * camelCase naming strategy to everything it serialises. A record here would silently emit
     * {@code imageUrl} and every vision call would fail with a message about a malformed content
     * part.
     */
    private static List<Object> contentMessages(List<AiMessage> messages) {
        List<Object> wire = new ArrayList<>(messages.size());
        for (AiMessage message : messages) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("role", message.role());
            entry.put("content", message.content());
            wire.add(entry);
        }
        return wire;
    }

    private JsonNode postForJson(AiEndpoint endpoint, String path, Map<String, Object> payload,
                                 String operation) {
        long started = System.nanoTime();
        long deadline = deadlineFrom(started, endpoint);
        return execute(endpoint, operation, started, () -> clientFor(endpoint).post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .headers(headers -> authorise(headers, endpoint))
            .body(payload)
            .exchange((request, response) -> readJson(endpoint, response, deadline)));
    }

    private JsonNode execute(AiEndpoint endpoint, String operation, long started,
                             java.util.function.Supplier<JsonNode> call) {
        try {
            JsonNode body = call.get();
            logCall(endpoint, operation, 200, started);
            return body;
        } catch (AiException e) {
            LOG.warn("AI {} to {} failed: {}", operation, endpoint.host(), e.getSlug());
            throw e;
        } catch (ResourceAccessException e) {
            AiException failure = transportFailure(endpoint, e);
            LOG.warn("AI {} to {} failed: {}", operation, endpoint.host(), failure.getSlug());
            throw failure;
        }
    }

    private AiException streamFailure(AiEndpoint endpoint, AiStreamListener listener,
                                      AiException failure) {
        LOG.warn("AI chatStream to {} failed: {}", endpoint.host(), failure.getSlug());
        listener.onError(failure);
        return failure;
    }

    /**
     * Maps a transport failure onto the family. A timeout is separated from everything else
     * because the two want different behaviour from a caller: a refused connection will be refused
     * again immediately, where a model that ran out of time may finish on a longer deadline.
     */
    private static AiException transportFailure(AiEndpoint endpoint, ResourceAccessException e) {
        // The ResourceAccessException is NOT carried as the cause, and that is the point of this
        // method rather than an oversight. Spring composes its message as
        // `I/O error on GET request for "<full uri>"`, and an AI endpoint's path or query
        // routinely carries the member's access token — several hosted gateways authenticate that
        // way, which is why AiSettingsService keeps the full URL out of the security event too.
        // GlobalExceptionHandler logs every 5xx with the whole throwable, so a cause here would
        // put that URL, token and all, into this install's logs on somebody else's outage. The
        // operator gets the host and the transport class instead, which is what a diagnosis
        // actually needs.
        LOG.warn("AI transport failure to {}: {}", endpoint.host(), transportClass(e));
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException || cause instanceof TimeoutException
                || cause instanceof org.apache.hc.client5.http.ConnectTimeoutException) {
                return new AiTimeoutException("The AI endpoint " + endpoint.host()
                    + " did not answer within the configured timeout");
            }
            if (cause instanceof UnknownHostException) {
                // The pinned resolver answers exactly one name. Anything else asked for — a
                // redirect to a second host, a client that decided to resolve on its own — lands
                // here rather than opening a connection nothing validated.
                return new AiUnreachableException("The AI endpoint " + endpoint.host()
                    + " redirected to a host this server will not resolve");
            }
            cause = cause.getCause() == cause ? null : cause.getCause();
        }
        return new AiUnreachableException("The AI endpoint " + endpoint.host()
            + " could not be reached");
    }

    /** The class name of the innermost transport failure — never its message, which has the URI. */
    private static String transportClass(Throwable e) {
        Throwable innermost = e;
        while (innermost.getCause() != null && innermost.getCause() != innermost) {
            innermost = innermost.getCause();
        }
        return innermost.getClass().getSimpleName();
    }

    /**
     * Cuts the connection rather than letting the client library drain it.
     *
     * <p>Without this the deadline and the size cap are advisory. Apache returns the body as an
     * {@code EofSensorInputStream} and closing one that has not been read to its end <em>reads it
     * to its end</em>, so that the connection can go back in the pool — which for the two cases
     * this client aborts on (an endpoint that dribbles, an endpoint that will not stop sending)
     * means the request thread keeps waiting for exactly the endpoint it just gave up on. A
     * connection nobody wants to reuse is worth less than the thread it costs.
     */
    private static void abortQuietly(InputStream in) {
        if (in instanceof EofSensorInputStream sensor) {
            try {
                sensor.abort();
            } catch (IOException e) {
                LOG.debug("Could not abort an AI connection: {}", e.getClass().getSimpleName());
            }
        }
    }

    /** The wall-clock instant an exchange started at {@code startedNanos} must be finished by. */
    private static long deadlineFrom(long startedNanos, AiEndpoint endpoint) {
        return startedNanos + endpoint.readTimeout().toNanos();
    }

    private JsonNode readJson(AiEndpoint endpoint, ClientHttpResponse response, long deadlineNanos)
        throws IOException {

        int status = response.getStatusCode().value();
        if (status < 200 || status >= 300) {
            throw statusFailure(endpoint, status);
        }
        String body = readCapped(endpoint, response.getBody(), deadlineNanos);
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException e) {
            // Deliberately without the body: an endpoint that answers 200 with HTML is usually a
            // proxy or a login page, and quoting it back would put whatever it contains into a
            // log line and an API response.
            throw new AiUnreachableException("The AI endpoint " + endpoint.host()
                + " answered with something that is not JSON", e);
        }
    }

    /**
     * Reads the SSE body frame by frame. Everything that is not a {@code data:} line — comments,
     * {@code event:}, {@code id:}, blank separators — is ignored, which is what the SSE
     * specification says a consumer should do and also what keeps a keep-alive comment from
     * reaching a client as an empty frame.
     */
    private AiCompletion readStream(AiEndpoint endpoint, ClientHttpResponse response,
                                    AiStreamListener listener, long deadlineNanos)
        throws IOException {

        int status = response.getStatusCode().value();
        if (status < 200 || status >= 300) {
            throw streamFailure(endpoint, listener, statusFailure(endpoint, status));
        }

        StringBuilder assembled = new StringBuilder();
        String model = endpoint.model();
        boolean done = false;

        try (BoundedLineReader reader =
                 new BoundedLineReader(response.getBody(), deadlineNanos)) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                String payload = line.substring(DATA_PREFIX.length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if (DONE_SENTINEL.equals(payload)) {
                    done = true;
                    break;
                }
                JsonNode frame;
                try {
                    frame = objectMapper.readTree(payload);
                } catch (JacksonException e) {
                    throw streamFailure(endpoint, listener, new AiUnreachableException(
                        "The AI endpoint " + endpoint.host()
                            + " sent a stream frame that is not JSON", e));
                }
                if (!frame.path("error").isMissingNode() && !frame.path("error").isNull()) {
                    // The in-band error frame of the §4.11 wire contract. Its own message is
                    // deliberately not repeated: an OpenAI-compatible server commonly echoes the
                    // request inside it, prompt included.
                    throw streamFailure(endpoint, listener, new AiUnreachableException(
                        "The AI endpoint " + endpoint.host()
                            + " reported an error part-way through the answer"));
                }
                String frameModel = text(frame, "model");
                if (frameModel != null && !frameModel.isBlank()) {
                    model = frameModel;
                }
                String delta = firstChoice(frame).path("delta").path("content").asString("");
                if (!delta.isEmpty()) {
                    assembled.append(delta);
                    listener.onDelta(delta);
                }
            }
        } catch (StreamAborted e) {
            throw streamFailure(endpoint, listener, e.timedOut()
                ? new AiTimeoutException("The AI endpoint " + endpoint.host()
                    + " did not finish the answer within the configured timeout")
                : new AiUnreachableException("The AI endpoint " + endpoint.host()
                    + " streamed more than the " + (MAX_RESPONSE_BYTES / (1024 * 1024))
                    + " MB this server accepts"));
        }

        if (!done) {
            throw streamFailure(endpoint, listener, new AiUnreachableException(
                "The AI endpoint " + endpoint.host() + " closed the stream before it finished"));
        }
        listener.onDone();
        return new AiCompletion(assembled.toString(), model, null, null);
    }

    private static AiException statusFailure(AiEndpoint endpoint, int status) {
        if (status == 401 || status == 403) {
            return new AiEndpointUnauthorizedException("The AI endpoint " + endpoint.host()
                + " rejected the stored API key");
        }
        if (status == 404) {
            return new AiModelMissingException("The AI endpoint " + endpoint.host()
                + " does not serve the model '" + endpoint.model() + "'");
        }
        return new AiUnreachableException("The AI endpoint " + endpoint.host() + " answered "
            + status);
    }

    /**
     * Drains a non-streaming body under both limits.
     *
     * <p>The deadline is the second one and it is not the same thing as the socket timeout the
     * request was configured with. That timeout bounds one <em>read</em>: an endpoint that emits a
     * byte just inside every read window satisfies it forever and holds this request thread and a
     * pooled connection for as long as it likes. {@code AiSettings} promises the member's stored
     * timeout is what they can hold a thread for; this is where that promise is kept.
     */
    private static String readCapped(AiEndpoint endpoint, InputStream in, long deadlineNanos)
        throws IOException {

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] chunk = new byte[READ_CHUNK_BYTES];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (body.size() + (long) read > MAX_RESPONSE_BYTES) {
                abortQuietly(in);
                throw new AiUnreachableException("The AI endpoint answered with more than "
                    + (MAX_RESPONSE_BYTES / (1024 * 1024)) + " MB, which this server will not "
                    + "read");
            }
            body.write(chunk, 0, read);
            if (System.nanoTime() - deadlineNanos >= 0) {
                abortQuietly(in);
                throw new AiTimeoutException("The AI endpoint " + endpoint.host()
                    + " did not answer within the configured timeout");
            }
        }
        return body.toString(StandardCharsets.UTF_8);
    }

    private static AiCompletion completionFrom(JsonNode body, String requestedModel) {
        JsonNode choice = firstChoice(body);
        String content = choice.path("message").path("content").asString("");
        String model = text(body, "model");
        JsonNode usage = body.path("usage");
        return new AiCompletion(content, model == null || model.isBlank() ? requestedModel : model,
            intOrNull(usage, "prompt_tokens"), intOrNull(usage, "completion_tokens"));
    }

    private static JsonNode firstChoice(JsonNode body) {
        JsonNode choices = body.path("choices");
        return choices.isArray() && !choices.isEmpty() ? choices.get(0) : body.path("__absent__");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asString() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private static Long longOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asLong() : null;
    }

    private static void logCall(AiEndpoint endpoint, String operation, int status, long startedAt) {
        LOG.debug("AI {} to {} answered {} in {} ms", operation, endpoint.host(), status,
            (System.nanoTime() - startedAt) / 1_000_000L);
    }

    /**
     * Raised inside {@link BoundedLineReader} and turned into the typed failure by
     * {@link #readStream}, which is the only place that knows the host and the listener.
     */
    private static final class StreamAborted extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final boolean timedOut;

        private StreamAborted(boolean timedOut) {
            super(null, null, false, false);
            this.timedOut = timedOut;
        }

        boolean timedOut() {
            return timedOut;
        }
    }

    /**
     * Reads SSE lines under a byte budget and a wall-clock deadline.
     *
     * <p>Written by hand because {@link java.io.BufferedReader#readLine()} cannot be made safe
     * here. It buffers characters without limit while it looks for a terminator, so a cap checked
     * once a line is complete never fires on the case that matters: a member's endpoint — theirs,
     * chosen by them, and this is a single JVM serving every household on the install — that sends
     * one {@code data:} line with no newline in it and keeps trickling bytes into it. This reader
     * counts what it takes off the socket, checks the budget after every read, and checks the
     * deadline before every read, so neither the heap nor the request thread depends on the
     * endpoint behaving.
     *
     * <p>It also counts BYTES rather than {@code String#length()}, which counted UTF-16 units and
     * undercounted every multi-byte answer — a 5 MB cap that admitted 15 MB of Japanese.
     */
    private static final class BoundedLineReader implements AutoCloseable {

        private final InputStream in;
        private final long deadlineNanos;
        private final byte[] chunk = new byte[READ_CHUNK_BYTES];
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();
        private int position;
        private int available;
        private long total;

        BoundedLineReader(InputStream in, long deadlineNanos) {
            this.in = in;
            this.deadlineNanos = deadlineNanos;
        }

        /** The next line without its terminator, or null once the stream has ended. */
        String readLine() throws IOException {
            while (true) {
                if (position == available) {
                    if (System.nanoTime() - deadlineNanos >= 0) {
                        abortQuietly(in);
                        throw new StreamAborted(true);
                    }
                    int read = in.read(chunk);
                    if (read == -1) {
                        return line.size() == 0 ? null : take();
                    }
                    position = 0;
                    available = read;
                    total += read;
                    if (total > MAX_RESPONSE_BYTES) {
                        abortQuietly(in);
                        throw new StreamAborted(false);
                    }
                }
                while (position < available) {
                    byte next = chunk[position++];
                    if (next == '\n') {
                        return take();
                    }
                    line.write(next);
                }
            }
        }

        private String take() {
            byte[] bytes = line.toByteArray();
            line.reset();
            int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r'
                ? bytes.length - 1 : bytes.length;
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
