package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.ai.client.AiEndpoint;
import finance.trove.ai.client.AiStreamListener;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The streaming half of {@link OpenAiCompatibleClient}, against the frame contract §4.11 pins:
 * OpenAI-style {@code data: {"choices":[{"delta":{"content":…}}]}} frames, the literal
 * {@code data: [DONE]} sentinel, and an in-band {@code data: {"error":…}} frame.
 *
 * <p>This is the contract collapse step 13's SSE controller forwards to the browser, so the shape
 * matters as much as the content: both clients' readers key off {@code [DONE]} rather than a named
 * event, and neither of them has any other way to learn that a generation failed part-way through.
 */
class OpenAiCompatibleStreamTest {

    private AiStubServer stub;
    private OpenAiCompatibleClient client;
    private final List<String> deltas = new ArrayList<>();
    private final AtomicInteger doneCalls = new AtomicInteger();
    private final List<AiException> errors = new ArrayList<>();

    private final AiStreamListener listener = new AiStreamListener() {

        @Override
        public void onDelta(String content) {
            deltas.add(content);
        }

        @Override
        public void onDone() {
            doneCalls.incrementAndGet();
        }

        @Override
        public void onError(AiException error) {
            errors.add(error);
        }
    };

    @BeforeEach
    void startStub() {
        stub = AiStubServer.start();
        client = AiClientSupport.client();
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    @DisplayName("deltas are surfaced one frame at a time and [DONE] ends the stream")
    void surfacesEveryDeltaThenDone() {
        stub.on("/chat/completions", (exchange, body) -> AiStubServer.writeSse(exchange, List.of(
            "data: " + frame("Spend "),
            "data: " + frame("less on "),
            "data: " + frame("coffee."),
            "data: [DONE]")));

        AiCompletion completion = client.chatStream(stub.endpoint(null, "qwen2.5:14b"),
            List.of(AiMessage.user("How do I save money?")), listener);

        assertThat(deltas)
            .as("step 13 forwards these frame by frame; joining them here would defeat the point")
            .containsExactly("Spend ", "less on ", "coffee.");
        assertThat(doneCalls).hasValue(1);
        assertThat(errors).isEmpty();
        assertThat(completion.content())
            .as("the assembled answer comes back so the caller can persist and audit it")
            .isEqualTo("Spend less on coffee.");

        JsonNode sent = JsonMapper.builder().build().readTree(stub.lastRequest().body());
        assertThat(sent.path("stream").asBoolean())
            .as("the request has to ask for a stream or the endpoint sends one JSON document")
            .isTrue();
    }

    @Test
    @DisplayName("frames that carry no content produce no delta")
    void ignoresContentlessFrames() {
        stub.on("/chat/completions", (exchange, body) -> AiStubServer.writeSse(exchange, List.of(
            ": keep-alive",
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}",
            "data: " + frame("hello"),
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}",
            "data: [DONE]")));

        client.chatStream(stub.endpoint(null, "llama3"), List.of(AiMessage.user("hi")), listener);

        assertThat(deltas)
            .as("a role-only opener and a finish frame would reach the browser as empty frames")
            .containsExactly("hello");
        assertThat(doneCalls).hasValue(1);
    }

    @Test
    @DisplayName("an in-band error frame reaches the listener and then throws")
    void inBandErrorFrame() {
        stub.on("/chat/completions", (exchange, body) -> AiStubServer.writeSse(exchange, List.of(
            "data: " + frame("Spend "),
            "data: {\"error\":{\"message\":\"context length exceeded for prompt 'my salary is'\","
                + "\"type\":\"invalid_request_error\"}}")));

        AiEndpoint endpoint = stub.endpoint(null, "llama3");
        assertThatThrownBy(() -> client.chatStream(endpoint,
            List.of(AiMessage.user("hi")), listener))
            .isInstanceOf(AiUnreachableException.class);

        assertThat(deltas).containsExactly("Spend ");
        assertThat(doneCalls)
            .as("a stream that failed did not finish, and must not look as though it did")
            .hasValue(0);
        assertThat(errors)
            .as("the listener is told as well as the caller: step 13 has already begun writing "
                + "its own response and needs to emit the in-band error frame")
            .hasSize(1);
        assertThat(errors.get(0).getMessage())
            .as("the endpoint's own message can quote the prompt back and is never repeated")
            .doesNotContain("my salary is");
    }

    @Test
    @DisplayName("a stream that stops before [DONE] is a truncation, not a short answer")
    void truncatedStream() {
        stub.on("/chat/completions", (exchange, body) -> AiStubServer.writeSse(exchange, List.of(
            "data: " + frame("You should definitely"))));

        AiEndpoint endpoint = stub.endpoint(null, "llama3");
        assertThatThrownBy(() -> client.chatStream(endpoint,
            List.of(AiMessage.user("hi")), listener))
            .isInstanceOf(AiUnreachableException.class)
            .hasMessageContaining("closed the stream");

        assertThat(doneCalls)
            .as("half a piece of financial advice presented as whole is the worse failure")
            .hasValue(0);
        assertThat(errors).hasSize(1);
    }

    @Test
    @DisplayName("a non-2xx on the streaming call fails before a single frame is read")
    void statusFailureOnAStream() {
        stub.status("/chat/completions", 401);

        AiEndpoint endpoint = stub.endpoint("sk-wrong", "llama3");
        assertThatThrownBy(() -> client.chatStream(endpoint,
            List.of(AiMessage.user("hi")), listener))
            .isInstanceOf(AiEndpointUnauthorizedException.class);

        assertThat(deltas).isEmpty();
        assertThat(errors).hasSize(1);
    }

    @Test
    @DisplayName("the model the endpoint reports mid-stream wins over the one that was asked for")
    void takesTheModelFromTheStream() {
        stub.on("/chat/completions", (exchange, body) -> AiStubServer.writeSse(exchange, List.of(
            "data: {\"model\":\"qwen2.5:14b-instruct\",\"choices\":[{\"delta\":"
                + "{\"content\":\"hi\"}}]}",
            "data: [DONE]")));

        AiCompletion completion = client.chatStream(stub.endpoint(null, "qwen2.5:14b"),
            List.of(AiMessage.user("hi")), listener);

        assertThat(completion.model()).isEqualTo("qwen2.5:14b-instruct");
    }

    @Test
    @DisplayName("one data: line with no newline in it is capped in flight, not at its end")
    void anUnterminatedLineIsCappedInFlight() {
        stub.on("/chat/completions", (exchange, body) -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            Arrays.fill(chunk, (byte) 'x');
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\""
                    .getBytes(StandardCharsets.UTF_8));
                for (int written = 0; written < 12 * 1024 * 1024; written += chunk.length) {
                    out.write(chunk);
                    out.flush();
                }
                // No newline, ever, and the connection stays open afterwards. A reader that only
                // checks its budget once a line is complete never checks it at all here: it grows
                // its own buffer until the heap is gone, which on this application is every
                // household's requests in one JVM.
                Thread.sleep(5_000);
            } catch (IOException e) {
                // swallow-ok: the client hanging up mid-write IS the pass condition
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AiEndpoint endpoint = stub.endpoint(null, "llama3", null, Duration.ofSeconds(2));

        assertThatThrownBy(() ->
            client.chatStream(endpoint, List.of(AiMessage.user("hi")), listener))
            .isInstanceOf(AiUnreachableException.class)
            .hasMessageContaining("5 MB");

        assertThat(errors)
            .as("the listener has to hear about it too — step 13 is mid-response")
            .hasSize(1);
        assertThat(doneCalls).hasValue(0);
    }

    @Test
    @DisplayName("a slow trickle cannot hold the thread past the caller's read timeout")
    void aDribblingStreamHitsTheDeadline() {
        stub.on("/chat/completions", (exchange, body) -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                // One byte every 50 ms: inside every read window, so the socket timeout never
                // fires, and 10 s of a request thread for a stream that says nothing.
                for (int i = 0; i < 200; i++) {
                    out.write('x');
                    out.flush();
                    Thread.sleep(50);
                }
            } catch (IOException e) {
                // swallow-ok: the client giving up mid-write IS the pass condition
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        AiEndpoint endpoint = stub.endpoint(null, "llama3", null, Duration.ofMillis(600));

        long started = System.nanoTime();
        assertThatThrownBy(() ->
            client.chatStream(endpoint, List.of(AiMessage.user("hi")), listener))
            .as("the read timeout is a deadline for the exchange, not a per-read idle timer")
            .isInstanceOf(AiTimeoutException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .as("it gave up on its own deadline rather than reading the trickle to its end")
            .isLessThan(Duration.ofSeconds(5));
        assertThat(errors).hasSize(1);
    }

    private static String frame(String content) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}";
    }
}
