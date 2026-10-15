package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import finance.trove.ai.client.AiEndpoint;
import finance.trove.ai.client.OpenAiCompatibleClient;
import finance.trove.ai.dto.AiMessage;
import finance.trove.app.support.LogCapture;
import finance.trove.common.net.ResolvedTarget;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Proves the three things that must never reach a log line: the API key, the prompt, and the
 * endpoint's response body.
 *
 * <p>Worth its own class because the failure it guards against is silent and permanent. A key or a
 * prompt in a log is not a bug that shows up as a broken feature — it shows up months later in a
 * log aggregator, on a shared screen, or in a support bundle a user emailed somewhere. Owner
 * decision 9 makes the endpoint per-user and the API key the member's own credential for a
 * third-party service; putting it in this install's logs would be a disclosure of a secret that is
 * not even Trove's to hold.
 *
 * <p>The level is forced to TRACE for the duration, which is the whole point: at the shipped
 * {@code finance.trove: INFO} the client's success path logs nothing at all and this assertion
 * would pass without proving anything. The failure path is exercised too, because that is where a
 * well-meaning "log what went wrong" tends to appear.
 */
class AiSecrecyTest {

    private static final String API_KEY = "sk-secret-key-do-not-log-8f3a";
    private static final String PROMPT = "My salary is 92000 and my rent is 2100";
    private static final String ANSWER = "You could move somewhere cheaper, Jane Doe";
    private static final String SECRET_PATH = "tenant-9f2c-do-not-log";
    private static final String SECRET_QUERY = "url-borne-credential-4b71";

    private AiStubServer stub;
    private OpenAiCompatibleClient client;
    private Logger troveLogger;
    private Level previousLevel;

    @BeforeEach
    void startStub() {
        stub = AiStubServer.start();
        client = AiClientSupport.client();
        troveLogger = (Logger) LoggerFactory.getLogger("finance.trove");
        previousLevel = troveLogger.getLevel();
        troveLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void stopStub() {
        troveLogger.setLevel(previousLevel);
        stub.close();
    }

    @Test
    @DisplayName("a successful call logs the host and nothing else that matters")
    void nothingSecretIsLoggedOnSuccess() {
        stub.json("/chat/completions",
            "{\"choices\":[{\"message\":{\"content\":\"" + ANSWER + "\"}}]}");

        try (LogCapture logs = LogCapture.start()) {
            client.chat(stub.endpoint(API_KEY, "qwen2.5:14b"), List.of(AiMessage.user(PROMPT)));

            assertThat(String.join("\n", logs.lines()))
                .doesNotContain(API_KEY)
                .doesNotContain(PROMPT)
                .doesNotContain(ANSWER)
                .doesNotContain("Jane Doe");
        }
    }

    @Test
    @DisplayName("a failed call logs the slug, not the endpoint's answer")
    void nothingSecretIsLoggedOnFailure() {
        stub.on("/chat/completions", (exchange, body) -> AiStubServer.writeJson(exchange, 400,
            "{\"error\":{\"message\":\"bad request for prompt: " + PROMPT + "\"}}"));
        AiEndpoint endpoint = stub.endpoint(API_KEY, "qwen2.5:14b");

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user(PROMPT))))
                .isInstanceOf(AiUnreachableException.class);

            String captured = String.join("\n", logs.lines());
            assertThat(captured)
                .doesNotContain(API_KEY)
                .doesNotContain(PROMPT);
            assertThat(captured)
                .as("something has to be logged, or the operator has nothing to go on")
                .contains("ai-unreachable");
        }
    }

    @Test
    @DisplayName("a transport failure carries no cause, so the endpoint's URL cannot reach the log")
    void theEndpointUrlNeverTravelsInACause() throws Exception {
        int deadPort = stub.port();
        stub.close();

        // Several hosted AI gateways authenticate on the URL itself, so the path and the query of
        // an endpoint are as secret as the key. Spring's ResourceAccessException says
        // `I/O error on GET request for "<full uri>"`, and GlobalExceptionHandler logs a 5xx with
        // the whole throwable — so carrying it as a cause would print exactly that.
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        URI uri = URI.create("http://127.0.0.1:" + deadPort + "/v1/tenant/" + SECRET_PATH
            + "?access_token=" + SECRET_QUERY);
        AiEndpoint endpoint = new AiEndpoint(
            new ResolvedTarget("127.0.0.1", loopback, deadPort, uri, List.of(loopback)),
            API_KEY, "llama3", null, Duration.ofMillis(300), Duration.ofMillis(300));

        try (LogCapture logs = LogCapture.start()) {
            assertThatThrownBy(() -> client.models(endpoint))
                .isInstanceOf(AiException.class)
                .satisfies(thrown -> {
                    assertThat(thrown.getCause())
                        .as("nothing downstream can strip a URL out of a chained message")
                        .isNull();
                    assertThat(renderChain(thrown))
                        .doesNotContain(SECRET_PATH)
                        .doesNotContain(SECRET_QUERY)
                        .doesNotContain(API_KEY);
                });

            String captured = String.join("\n", logs.lines());
            assertThat(captured)
                .doesNotContain(SECRET_PATH)
                .doesNotContain(SECRET_QUERY)
                .doesNotContain(API_KEY);
            assertThat(captured)
                .as("the operator still gets the host and what kind of failure it was")
                .contains("127.0.0.1");
        }
    }

    /** What {@code GlobalExceptionHandler}'s {@code LOG.error(..., ex)} would render. */
    private static String renderChain(Throwable thrown) {
        StringBuilder chain = new StringBuilder();
        for (Throwable link = thrown; link != null;
             link = link.getCause() == link ? null : link.getCause()) {
            chain.append(link.getClass().getName()).append(": ").append(link.getMessage())
                .append('\n');
        }
        return chain.toString();
    }

    @Test
    @DisplayName("the exception a user sees carries the host and never the key")
    void nothingSecretIsInTheExceptionMessage() {
        stub.status("/chat/completions", 401);
        AiEndpoint endpoint = stub.endpoint(API_KEY, "qwen2.5:14b");

        assertThatThrownBy(() -> client.chat(endpoint, List.of(AiMessage.user(PROMPT))))
            .isInstanceOf(AiEndpointUnauthorizedException.class)
            .hasMessageContaining("127.0.0.1")
            .hasMessageNotContaining(API_KEY)
            .hasMessageNotContaining(PROMPT);
    }
}
