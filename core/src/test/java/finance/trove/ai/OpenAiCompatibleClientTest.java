package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.ai.client.AiEndpoint;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.ai.dto.AiModel;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The non-streaming half of {@link OpenAiCompatibleClient}: chat, vision, the model list, the
 * mapping from an endpoint's answer onto the typed exception family, and the two hardening
 * properties that are invisible from the outside — the response cap and the bearer header.
 */
class OpenAiCompatibleClientTest {

    private static final String CHAT_OK = """
        {"model":"qwen2.5:14b",
         "choices":[{"message":{"role":"assistant","content":"Spend less on coffee."}}],
         "usage":{"prompt_tokens":41,"completion_tokens":7}}
        """;

    private AiStubServer stub;
    private OpenAiCompatibleClient client;

    @BeforeEach
    void startStub() {
        stub = AiStubServer.start();
        client = AiClientSupport.client();
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Nested
    @DisplayName("chat")
    class Chat {

        @Test
        @DisplayName("sends the OpenAI request shape and reads the answer back")
        void completesAChat() {
            stub.json("/chat/completions", CHAT_OK);

            AiCompletion completion = client.chat(stub.endpoint(null, "qwen2.5:14b"),
                List.of(AiMessage.system("Be brief."), AiMessage.user("How do I save money?")));

            assertThat(completion.content()).isEqualTo("Spend less on coffee.");
            assertThat(completion.model()).isEqualTo("qwen2.5:14b");
            assertThat(completion.promptTokens()).isEqualTo(41);
            assertThat(completion.completionTokens()).isEqualTo(7);

            JsonNode sent = parse(stub.lastRequest().body());
            assertThat(sent.path("model").asString()).isEqualTo("qwen2.5:14b");
            assertThat(sent.path("stream").asBoolean()).isFalse();
            assertThat(sent.path("messages")).hasSize(2);
            assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("system");
            assertThat(sent.path("messages").get(1).path("content").asString())
                .isEqualTo("How do I save money?");
        }

        @Test
        @DisplayName("an endpoint that reports no usage still yields a completion")
        void tolerateMissingUsage() {
            stub.json("/chat/completions",
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

            AiCompletion completion =
                client.chat(stub.endpoint(null, "llama3"), List.of(AiMessage.user("hi")));

            assertThat(completion.content()).isEqualTo("ok");
            assertThat(completion.promptTokens()).isNull();
            assertThat(completion.model())
                .as("with no model in the answer, the one that was asked for is recorded")
                .isEqualTo("llama3");
        }
    }

    @Nested
    @DisplayName("the API key")
    class ApiKey {

        @Test
        @DisplayName("travels as a bearer token when one is configured")
        void sendsTheBearerHeader() {
            stub.json("/chat/completions", CHAT_OK);

            client.chat(stub.endpoint("sk-test-key", "qwen2.5:14b"),
                List.of(AiMessage.user("hi")));

            assertThat(stub.lastRequest().header("Authorization"))
                .isEqualTo("Bearer sk-test-key");
        }

        @Test
        @DisplayName("is omitted entirely for an endpoint that needs none")
        void sendsNoHeaderWithoutAKey() {
            stub.json("/models", "{\"data\":[]}");

            client.models(stub.endpoint(null, "llama3"));

            assertThat(stub.lastRequest().header("Authorization"))
                .as("a local Ollama needs no key and must not be sent an empty bearer")
                .isNull();
        }
    }

    @Nested
    @DisplayName("vision")
    class Vision {

        @Test
        @DisplayName("sends the prompt and the image as two content parts, the image inline")
        void sendsContentParts() {
            stub.json("/chat/completions", CHAT_OK);
            byte[] image = "not-really-a-jpeg".getBytes(StandardCharsets.UTF_8);

            client.vision(stub.endpoint(null, "qwen2.5:14b", "qwen2.5-vl:7b",
                Duration.ofSeconds(5)), "Read this receipt.", image, "image/jpeg");

            JsonNode sent = parse(stub.lastRequest().body());
            assertThat(sent.path("model").asString())
                .as("a vision call uses the vision model, not the chat model")
                .isEqualTo("qwen2.5-vl:7b");

            JsonNode content = sent.path("messages").get(0).path("content");
            assertThat(content.isArray()).isTrue();
            assertThat(content.get(0).path("type").asString()).isEqualTo("text");
            assertThat(content.get(0).path("text").asString()).isEqualTo("Read this receipt.");
            assertThat(content.get(1).path("type").asString()).isEqualTo("image_url");
            assertThat(content.get(1).path("image_url").path("url").asString())
                .as("the image travels inline as a data: URI — never as a URL the endpoint fetches")
                .startsWith("data:image/jpeg;base64,")
                .endsWith(java.util.Base64.getEncoder().encodeToString(image));
        }

        @Test
        @DisplayName("refuses before connecting when no vision model is configured")
        void refusesWithoutAVisionModel() {
            AiEndpoint endpoint = stub.endpoint(null, "qwen2.5:14b");

            assertThatThrownBy(() -> client.vision(endpoint, "Read this.", new byte[] {1},
                "image/png"))
                .isInstanceOf(AiModelMissingException.class);
            assertThat(stub.received())
                .as("nothing may leave the process when the call cannot be made")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("models")
    class Models {

        @Test
        @DisplayName("reads the documented {\"data\":[...]} envelope")
        void readsTheEnvelope() {
            stub.json("/models", """
                {"object":"list","data":[
                  {"id":"qwen2.5:14b","object":"model","created":1735689600,"owned_by":"library"},
                  {"id":"llama3","object":"model"}]}
                """);

            List<AiModel> models = client.models(stub.endpoint(null, "qwen2.5:14b"));

            assertThat(models).containsExactly(
                new AiModel("qwen2.5:14b", "library", 1735689600L),
                new AiModel("llama3", null, null));
        }

        @Test
        @DisplayName("tolerates a bare array, which several self-hosted servers return")
        void readsABareArray() {
            stub.json("/models", "[{\"id\":\"mistral\"}]");

            assertThat(client.models(stub.endpoint(null, "mistral")))
                .containsExactly(new AiModel("mistral", null, null));
        }

        @Test
        @DisplayName("drops an entry with no id rather than surfacing a nameless model")
        void dropsEntriesWithoutAnId() {
            stub.json("/models", "{\"data\":[{\"object\":\"model\"},{\"id\":\"real\"}]}");

            assertThat(client.models(stub.endpoint(null, "real")))
                .containsExactly(new AiModel("real", null, null));
        }
    }

    @Nested
    @DisplayName("what the endpoint's failures become")
    class Failures {

        @Test
        @DisplayName("401 and 403 are a rejected key, not a rejected session")
        void unauthorised() {
            stub.status("/chat/completions", 401);
            AiEndpoint endpoint = stub.endpoint("sk-wrong", "llama3");

            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiEndpointUnauthorizedException.class)
                .hasMessageContaining("127.0.0.1");
        }

        @Test
        @DisplayName("403 lands in the same place as 401")
        void forbidden() {
            stub.status("/chat/completions", 403);
            AiEndpoint endpoint = stub.endpoint("sk-wrong", "llama3");

            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiEndpointUnauthorizedException.class);
        }

        @Test
        @DisplayName("404 names the model the settings asked for")
        void modelMissing() {
            stub.status("/chat/completions", 404);
            AiEndpoint endpoint = stub.endpoint(null, "does-not-exist");

            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiModelMissingException.class)
                .hasMessageContaining("does-not-exist");
        }

        @Test
        @DisplayName("any other status is an unreachable endpoint, never a Trove 500")
        void otherStatuses() {
            stub.status("/chat/completions", 503);
            AiEndpoint endpoint = stub.endpoint(null, "llama3");

            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiUnreachableException.class);
        }

        @Test
        @DisplayName("a 200 that is not JSON is refused without quoting what it was")
        void nonJsonAnswer() {
            stub.on("/chat/completions", (exchange, body) -> {
                byte[] html = "<html><body>Sign in to the proxy</body></html>"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, html.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(html);
                }
            });
            AiEndpoint endpoint = stub.endpoint(null, "llama3");

            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiUnreachableException.class)
                .hasMessageNotContaining("Sign in to the proxy");
        }

        @Test
        @DisplayName("a nothing-in-particular connection failure is unreachable, not a timeout")
        void connectionRefused() {
            AiEndpoint endpoint = stub.endpoint(null, "llama3");
            stub.close();

            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiException.class)
                .extracting(e -> ((AiException) e).getSlug())
                .isEqualTo("ai-unreachable");
        }

        @Test
        @DisplayName("an endpoint that never answers becomes ai-timeout, on the caller's deadline")
        void readTimeout() {
            stub.on("/chat/completions", (exchange, body) -> {
                try {
                    Thread.sleep(2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                AiStubServer.writeJson(exchange, 200, CHAT_OK);
            });
            AiEndpoint endpoint =
                stub.endpoint(null, "llama3", null, Duration.ofMillis(300));

            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiTimeoutException.class);
        }

        @Test
        @DisplayName("a response larger than the cap is refused rather than read into the heap")
        void oversizedResponse() {
            stub.on("/chat/completions", (exchange, body) -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) 'x');
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write('"');
                    for (int written = 0; written < 6 * 1024 * 1024; written += chunk.length) {
                        out.write(chunk);
                    }
                    out.write('"');
                }
            });
            AiEndpoint endpoint = stub.endpoint(null, "llama3");

            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiUnreachableException.class)
                .hasMessageContaining("MB");
        }

        @Test
        @DisplayName("an endpoint that dribbles a byte at a time still ends on the deadline")
        void aDribblingEndpointHitsTheDeadline() {
            stub.on("/chat/completions", (exchange, body) -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    // Inside the read window every time, so the socket timeout never fires and
                    // the caller's stored readTimeoutMs would bound nothing at all.
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
            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user("hi"))))
                .isInstanceOf(AiTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("a read timeout that only bounds one read bounds nothing")
                .isLessThan(Duration.ofSeconds(5));
        }
    }

    private static JsonNode parse(String json) {
        return JsonMapper.builder().build().readTree(json);
    }
}
