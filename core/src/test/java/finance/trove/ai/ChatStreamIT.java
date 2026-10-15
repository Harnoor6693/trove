package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ai.chat.CoachChatService;
import finance.trove.ai.chat.CoachContextBuilder;
import finance.trove.ai.chat.CoachConversationService;
import finance.trove.ai.chat.CoachFrameSink;
import finance.trove.ai.chat.CoachProperties;
import finance.trove.ai.chat.CoachPromptsProperties;
import finance.trove.ai.chat.CoachTurn;
import finance.trove.ai.chat.dto.ChatCompletionRequest;
import finance.trove.ai.chat.dto.ChatTurnRequest;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.common.time.UserClock;
import finance.trove.users.UserRepository;
import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * §9 risk 11: the coach really streams — and the route really speaks SSE
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §9 risk 11, §4.11).
 *
 * <p>The risk this class exists for is "streaming coach works only in unit tests, or is silently
 * buffered". It has two halves and they are provable in two different places, so this class proves
 * both rather than one and calling it done.
 *
 * <ol>
 *   <li><strong>Five upstream chunks reach the client as five separate frames, incrementally.</strong>
 *       Proved against the real {@code OpenAiCompatibleClient} and a real chunked HTTP response
 *       from {@link AiStubServer}, with the timing asserted rather than assumed: the first frame
 *       must reach the sink <em>before</em> the stub has written the last one. If anything in the
 *       path buffered the answer, that assertion is arithmetically impossible.
 *       <p>The gateway is stubbed because it has to be: {@link AiStubServer} binds to loopback and
 *       {@code OutboundUrlPolicy} refuses loopback unless {@code TROVE_EGRESS_ALLOW_PRIVATE} is
 *       set, which no integration test can flip without forking a second Spring context. See
 *       {@link CoachStubGateway}. Everything below the gateway is the real thing — the real client,
 *       the real service, the real encryption, the real Postgres.</li>
 *   <li><strong>The route answers a real SSE response through the whole filter chain.</strong>
 *       Proved over HTTP against the running application: 200, {@code text/event-stream},
 *       {@code X-Session-Id}, {@code data: } frames rather than a JSON envelope, and a failure
 *       after the response opened arriving in band with no {@code [DONE]} after it. The endpoint
 *       is a routable-looking address nothing answers on, which is the only kind this suite can
 *       reach through the real egress policy — so what this half proves is the response shape and
 *       the error frame, not the happy path, which the first half covers.
 *       <p>{@code AuditLogFilterTest#theResponseIsNotWrapped} holds the third piece: the filter
 *       does not wrap the response in a {@code ContentCachingResponseWrapper}, which is the
 *       specific regression §9 risk 11 names. Confirmed in the merged filter — there is no
 *       reference to that class anywhere in {@code finance.trove.app.audit}.</li>
 * </ol>
 */
class ChatStreamIT extends AuthFlowSupport {

    /** Long enough that "all at once at the end" and "as they arrive" cannot be confused. */
    private static final long FRAME_GAP_MILLIS = 60;

    private static final List<String> CHUNKS =
        List.of("You ", "spent ", "the most ", "on ", "Groceries.");

    @Autowired
    private CoachConversationService conversations;

    @Autowired
    private CoachContextBuilder contextBuilder;

    @Autowired
    private CoachPromptsProperties prompts;

    @Autowired
    private CoachProperties coachProperties;

    @Autowired
    private UserRepository users;

    @Autowired
    private UserClock clock;

    private AiStubServer stub;
    private CoachStubGateway gateway;

    @BeforeEach
    void startStub() {
        stub = AiStubServer.start();
        gateway = new CoachStubGateway(stub);
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    @DisplayName("five stubbed chunks reach the client as five frames plus [DONE], incrementally")
    void fiveChunksArriveAsFiveFramesBeforeTheRequestCompletes() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-stream");
        AtomicLong lastChunkWrittenAt = new AtomicLong();
        stub.on("/chat/completions", (exchange, body) -> writeChunksSlowly(exchange,
            lastChunkWrittenAt));

        RecordingSink sink = new RecordingSink();
        CoachChatService service = coach();
        CoachTurn turn = service.prepare(member.userId(), member.householdId(), null,
            ask("What did I spend the most on this month?"));

        service.stream(turn, sink);

        assertThat(sink.deltas)
            .as("each upstream chunk is one frame; a buffered answer would arrive as one")
            .containsExactlyElementsOf(CHUNKS);
        assertThat(sink.doneCount)
            .as("the stream ends with exactly one [DONE]")
            .isOne();
        assertThat(sink.failures).isEmpty();
        assertThat(sink.deltaTimes.get(0))
            .as("the first frame must reach the client BEFORE the endpoint has written the last "
                + "one. Anything buffering the body — a ContentCachingResponseWrapper in the "
                + "audit filter, a client that reads the whole entity before parsing — makes this "
                + "impossible (§9 risk 11)")
            .isLessThan(lastChunkWrittenAt.get());
        assertThat(sink.deltaTimes.get(sink.deltaTimes.size() - 1) - sink.deltaTimes.get(0))
            .as("the five frames are spread over the generation, not delivered together")
            .isGreaterThanOrEqualTo(2 * FRAME_GAP_MILLIS);

        assertThat(gateway.lastSensitivity())
            .as("every coach payload is the household's ledger")
            .isEqualTo(AiSensitivity.SENSITIVE);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
            "select role, content_encrypted, model_used from coach_message "
                + "where conversation_id = ? and role = 'ASSISTANT'", turn.conversationId());
        assertThat((String) stored.get("content_encrypted"))
            .as("the assembled answer is stored, and stored encrypted")
            .startsWith("v1:")
            .doesNotContain("Groceries");
        assertThat(stored.get("model_used")).isEqualTo(CoachStubGateway.MODEL);
    }

    @Test
    @DisplayName("a stream that fails mid-flight ends with the in-band error frame, not [DONE]")
    void aFailedStreamEndsWithAnErrorFrame() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-stream-fail");
        // Two frames, then the response ends without the sentinel: a truncated stream, which the
        // client reports as a failure rather than as a complete short answer.
        stub.on("/chat/completions", (exchange, body) -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(frame("Your ").getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.write(frame("budget").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        });

        RecordingSink sink = new RecordingSink();
        CoachChatService service = coach();
        CoachTurn turn = service.prepare(member.userId(), member.householdId(), null,
            ask("Am I over budget?"));

        service.stream(turn, sink);

        assertThat(sink.deltas).containsExactly("Your ", "budget");
        assertThat(sink.doneCount)
            .as("a truncated answer never gets the [DONE] sentinel")
            .isZero();
        assertThat(sink.failures)
            .as("the failure arrives in band, with a slug from the contract's enum")
            .hasSize(1);
        assertThat(sink.failures.get(0).slug()).startsWith("ai-");
        assertThat(sink.failures.get(0).detail())
            .as("never a stack trace, a prompt, an endpoint host or the endpoint's own body")
            .doesNotContain("java.")
            .doesNotContain("127.0.0.1")
            .doesNotContain("Am I over budget?");

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_message where conversation_id = ? and role = 'USER'",
            Long.class, turn.conversationId()))
            .as("the question is written before the call, so a failed answer never loses it")
            .isOne();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_message where conversation_id = ? and role = 'ASSISTANT'",
            Long.class, turn.conversationId()))
            .as("the partial answer is persisted as what it was — truncated, not discarded")
            .isOne();
    }

    @Test
    @DisplayName("the route answers text/event-stream with X-Session-Id, through the real chain")
    void theRouteStreamsThroughTheWholeFilterChain() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-stream-route");
        enableAiPointingAtNowhere(member);

        HttpResponse<String> response = postJson(appUrl("/v1/chat/completions?stream=true"),
            Json.write(Map.of("messages",
                List.of(Map.of("role", "user", "content", "Where did my money go?")))),
            "Authorization", "Bearer " + member.accessToken());

        assertThat(response.statusCode())
            .as("everything that can fail before the first byte already has; the stream is a 200. "
                + "Body: %s", response.body())
            .isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
            .as("declared as text/event-stream in openapi/trove-core.yaml, and answered as one")
            .startsWith("text/event-stream");
        assertThat(response.headers().firstValue("X-Session-Id"))
            .as("X-Session-Id is set on EVERY completion response, streaming or not (§4.11)")
            .isPresent();
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();

        String body = response.body();
        assertThat(body)
            .as("the body is SSE frames, not the JSON envelope")
            .startsWith("data: ")
            .contains("\"error\"")
            .doesNotContain("[DONE]")
            .doesNotContain("\"success\"");
        assertThat(Json.parse(body.substring("data: ".length()).trim())
            .path("error").path("type").asString())
            .as("an AI slug from the contract's enum, never a bare 500")
            .startsWith("https://trove.finance/errors/ai-");
        assertThat(body)
            .as("no stack trace, no prompt and no endpoint host reaches the member")
            .doesNotContain("java.")
            .doesNotContain("203.0.113.10")
            .doesNotContain("Where did my money go?");

        String sessionId = response.headers().firstValue("X-Session-Id").orElseThrow();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_message where conversation_id = cast(? as uuid) "
                + "and role = 'USER'", Long.class, sessionId))
            .as("the question survived a call that never produced an answer")
            .isOne();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_message where conversation_id = cast(? as uuid) "
                + "and role = 'ASSISTANT'", Long.class, sessionId))
            .as("a blank answer is not written as an empty turn")
            .isZero();
    }

    /**
     * The production service, with the real conversation store, context builder, prompts and clock,
     * and only the gateway replaced. See the class javadoc for why the gateway has to be.
     */
    private CoachChatService coach() {
        return new CoachChatService(gateway, conversations, contextBuilder, prompts,
            coachProperties, users, clock);
    }

    private static ChatCompletionRequest ask(String question) {
        return new ChatCompletionRequest(List.of(new ChatTurnRequest("user", question)));
    }

    /**
     * Enables AI for {@code member} against a TEST-NET-3 address, written straight to the table.
     *
     * <p>{@code PUT /v1/ai/settings} would accept this URL — {@code AiPolicyIT} proves a public
     * https endpoint is accepted — but going through the route would also mean the settings screen
     * has to succeed for a stream test to run, and this test is about the stream. Nothing here
     * bypasses a security decision: the egress policy still runs on the call itself, and the whole
     * point is that it lets this address through and then nothing answers on it.
     */
    private void enableAiPointingAtNowhere(Session member) {
        jdbcTemplate.update("""
            INSERT INTO ai_settings (user_id, enabled, endpoint_url, model, privacy_tier,
                                     connect_timeout_ms, read_timeout_ms)
            VALUES (?, true, 'https://203.0.113.10/v1', 'probe-model', 'LOCAL_ONLY', 1000, 2000)
            """, member.userId());
    }

    private static String frame(String content) {
        return "data: {\"choices\":[{\"delta\":{\"content\":\"" + content + "\"}}]}\n\n";
    }

    /** Writes the five chunks with a real gap between them, recording when the last one went out. */
    private static void writeChunksSlowly(com.sun.net.httpserver.HttpExchange exchange,
                                          AtomicLong lastChunkWrittenAt) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (String chunk : CHUNKS) {
                out.write(frame(chunk).getBytes(StandardCharsets.UTF_8));
                out.flush();
                lastChunkWrittenAt.set(System.currentTimeMillis());
                sleep();
            }
            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(FRAME_GAP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while pacing the stub's frames", e);
        }
    }

    /** Records what reached the client, and when. */
    private static final class RecordingSink implements CoachFrameSink {

        private final List<String> deltas = new ArrayList<>();
        private final List<Long> deltaTimes = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();
        private int doneCount;

        @Override
        public void delta(String content) {
            deltas.add(content);
            deltaTimes.add(System.currentTimeMillis());
        }

        @Override
        public void done() {
            doneCount++;
        }

        @Override
        public void failure(int status, String slug, String detail) {
            failures.add(new Failure(status, slug, detail));
        }

        private record Failure(int status, String slug, String detail) {
        }
    }
}
