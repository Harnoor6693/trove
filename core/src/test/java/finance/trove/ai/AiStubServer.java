package finance.trove.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import finance.trove.ai.client.AiEndpoint;
import finance.trove.common.net.ResolvedTarget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A throwaway OpenAI-compatible server on loopback, for the unit tests of
 * {@link finance.trove.ai.client.OpenAiCompatibleClient}.
 *
 * <p>The JDK's own {@code com.sun.net.httpserver} rather than WireMock, which is on the test
 * classpath and would do most of this. WireMock is a stubbing DSL over a matcher engine, and what
 * these tests need is the opposite: control of the <em>bytes on the wire</em> — a chunked SSE body
 * written frame by frame, a stream cut off mid-answer, a response larger than the client's cap, a
 * handler that simply does not answer until the read timeout fires. Those are properties of a
 * socket, not of a stub, and expressing them through a matcher DSL is more indirection than
 * fifteen lines of handler.
 *
 * <p>Bound to {@code 127.0.0.1} on an ephemeral port. That is a loopback address and
 * {@code OutboundUrlPolicy} would refuse it outright — which is correct, and is why these tests
 * build the {@link ResolvedTarget} directly rather than going through the policy. The policy's own
 * decision table is {@code OutboundUrlPolicyTest}'s subject; what is under test here is the client
 * that runs after a target has been approved.
 */
final class AiStubServer implements AutoCloseable {

    /** What a handler does with one request. */
    @FunctionalInterface
    interface Responder {
        void respond(HttpExchange exchange, String requestBody) throws IOException;
    }

    /** One request the stub saw, so a test can assert on what the client actually sent. */
    record Received(String method, String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            List<String> values = headers().get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }
    }

    private final HttpServer server;
    private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Responder> responders = new ConcurrentHashMap<>();

    private AiStubServer(HttpServer server) {
        this.server = server;
    }

    static AiStubServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AiStubServer stub = new AiStubServer(server);
            server.createContext("/", stub::dispatch);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Registers what to do for a path under the {@code /v1} base, e.g. {@code /chat/completions}. */
    AiStubServer on(String path, Responder responder) {
        responders.put("/v1" + path, responder);
        return this;
    }

    /** Answers {@code path} with this JSON and a 200. */
    AiStubServer json(String path, String body) {
        return on(path, (exchange, requestBody) -> writeJson(exchange, 200, body));
    }

    /** Answers {@code path} with this status and an empty-ish JSON body. */
    AiStubServer status(String path, int status) {
        return on(path, (exchange, requestBody) ->
            writeJson(exchange, status, "{\"error\":{\"message\":\"nope\"}}"));
    }

    List<Received> received() {
        return List.copyOf(received);
    }

    Received lastRequest() {
        List<Received> all = received();
        if (all.isEmpty()) {
            throw new AssertionError("the stub received no request at all");
        }
        return all.get(all.size() - 1);
    }

    int port() {
        return server.getAddress().getPort();
    }

    /**
     * An endpoint pointing at this stub, with the pinned target built by hand.
     *
     * @param apiKey      the key the client should present, or null for none
     * @param readTimeout how long the client waits, so a test can make a slow handler time out
     */
    AiEndpoint endpoint(String apiKey, String model, String visionModel, Duration readTimeout) {
        InetAddress loopback;
        try {
            loopback = InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            throw new IllegalStateException("127.0.0.1 does not resolve", e);
        }
        URI uri = URI.create("http://127.0.0.1:" + port() + "/v1");
        ResolvedTarget target =
            new ResolvedTarget("127.0.0.1", loopback, port(), uri, List.of(loopback));
        return new AiEndpoint(target, apiKey, model, visionModel, Duration.ofSeconds(2),
            readTimeout);
    }

    AiEndpoint endpoint(String apiKey, String model) {
        return endpoint(apiKey, model, null, Duration.ofSeconds(5));
    }

    static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Writes SSE frames on a chunked response, flushing each one, so the client sees them arrive
     * separately rather than as one buffered blob — which is the whole property the streaming test
     * is about.
     */
    static void writeSse(HttpExchange exchange, List<String> frames) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String frame : frames) {
                out.write((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String body = read(exchange.getRequestBody());
        received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            Map.copyOf(exchange.getRequestHeaders()), body));
        Responder responder = responders.get(exchange.getRequestURI().getPath());
        if (responder == null) {
            writeJson(exchange, 404, "{\"error\":{\"message\":\"no stub for this path\"}}");
            return;
        }
        responder.respond(exchange, body);
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        in.transferTo(buffer);
        return buffer.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
