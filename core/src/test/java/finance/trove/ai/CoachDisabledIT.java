package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.ai.chat.CoachChatService;
import finance.trove.ai.chat.CoachContextBuilder;
import finance.trove.ai.chat.CoachConversationService;
import finance.trove.ai.chat.CoachProperties;
import finance.trove.ai.chat.CoachPromptsProperties;
import finance.trove.ai.chat.dto.ChatCompletionRequest;
import finance.trove.ai.chat.dto.ChatTurnRequest;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.common.time.UserClock;
import finance.trove.users.UserRepository;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * AI is off by default and nothing in Trove requires it
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6; {@code AiDisabledException}).
 *
 * <p>A member who has never configured an endpoint gets 409 {@code ai-disabled} from the one route
 * that needs one, and 200 from every route that does not. The clients render that 409 as "configure
 * your AI endpoint in Settings" rather than as an error, which is why it is a 409 and not a 500 or
 * an empty 200.
 *
 * <p>The assertion that separates a free feature from a bill is the one about the stub: nothing is
 * contacted, no connection is opened, and no conversation row is written, because
 * {@code AiHandle.isEnabled()} is checked before any of that — it reads a snapshot and opens
 * nothing.
 */
class CoachDisabledIT extends AuthFlowSupport {

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

    @BeforeEach
    void startStub() {
        stub = AiStubServer.start();
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    @DisplayName("a member with no AI settings gets 409 ai-disabled on both completion shapes")
    void bothCompletionShapesAnswer409() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-off");
        String body = Json.write(Map.of("messages",
            List.of(Map.of("role", "user", "content", "What did I spend on this month?"))));

        HttpResponse<String> plain = postJson(appUrl("/v1/chat/completions"), body,
            "Authorization", "Bearer " + member.accessToken());
        assertThat(plain.statusCode()).as(plain.body()).isEqualTo(409);
        assertThat(Json.errorCode(plain.body())).isEqualTo("ai-disabled");

        HttpResponse<String> streaming = postJson(appUrl("/v1/chat/completions?stream=true"), body,
            "Authorization", "Bearer " + member.accessToken());
        assertThat(streaming.statusCode())
            .as("asking for a stream does not turn a configuration problem into a 200 with an "
                + "error frame — everything decidable before the first byte is decided first")
            .isEqualTo(409);
        assertThat(streaming.headers().firstValue("content-type").orElse(""))
            .as("a non-2xx answer is the ordinary JSON envelope, whatever stream said (§4.11)")
            .startsWith("application/json");
        assertThat(Json.errorCode(streaming.body())).isEqualTo("ai-disabled");

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_conversation where user_id = ?", Long.class,
            member.userId()))
            .as("a refused question opens no conversation")
            .isZero();
    }

    @Test
    @DisplayName("the gateway is never reached when AI is off — no connection, no bill")
    void theGatewayIsNeverReached() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-off-gateway");
        CoachStubGateway gateway = new CoachStubGateway(stub).disabled();
        CoachChatService coach = new CoachChatService(gateway, conversations, contextBuilder,
            prompts, coachProperties, users, clock);

        assertThatThrownBy(() -> coach.prepare(member.userId(), member.householdId(), null,
            new ChatCompletionRequest(List.of(new ChatTurnRequest("user", "Anything?")))))
            .isInstanceOf(AiDisabledException.class);

        assertThat(gateway.callCount())
            .as("isEnabled() reads a snapshot; nothing below it is touched")
            .isZero();
        assertThat(stub.received())
            .as("no socket is opened to anybody's endpoint")
            .isEmpty();
    }

    @Test
    @DisplayName("every route that needs no endpoint still answers with AI switched off")
    void theRestOfTheSurfaceStillWorks() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-off-rest");

        HttpResponse<String> suggestions =
            get(appUrl("/v1/chat/suggestions"), member.accessToken());
        assertThat(suggestions.statusCode()).as(suggestions.body()).isEqualTo(200);
        assertThat(Json.elements(Json.data(suggestions.body())))
            .as("the list is what invites a member to switch AI on; it cannot need AI to render")
            .isNotEmpty();

        HttpResponse<String> history = get(appUrl("/v1/chat/history"), member.accessToken());
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(history.body()))).isEmpty();

        HttpResponse<String> insights = get(appUrl("/v1/insights"), member.accessToken());
        assertThat(insights.statusCode()).isEqualTo(200);
        assertThat(Json.elements(Json.data(insights.body()))).isEmpty();
        assertThat(Json.parse(insights.body()).path("meta").path("total").asInt()).isZero();

        assertThat(delete(appUrl("/v1/chat/history"), null,
            "Authorization", "Bearer " + member.accessToken()).statusCode())
            .as("clearing an empty history is a 200, not a 404")
            .isEqualTo(200);

        assertThat(stub.received()).isEmpty();
    }
}
