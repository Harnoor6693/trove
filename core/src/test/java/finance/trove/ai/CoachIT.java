package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.ai.chat.CoachChatService;
import finance.trove.ai.chat.CoachContextBuilder;
import finance.trove.ai.chat.CoachConversationService;
import finance.trove.ai.chat.CoachProperties;
import finance.trove.ai.chat.CoachPromptsProperties;
import finance.trove.ai.chat.CoachTurn;
import finance.trove.ai.chat.dto.ChatCompletionRequest;
import finance.trove.ai.chat.dto.ChatCompletionResponse;
import finance.trove.ai.chat.dto.ChatTurnRequest;
import finance.trove.app.support.AuthFlowSupport;
import finance.trove.app.support.Json;
import finance.trove.common.time.UserClock;
import finance.trove.users.UserRepository;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A conversation's whole life: created, continued, listed, read and cleared — and invisible to a
 * household mate ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11, §1.5).
 *
 * <p><strong>The one thing this class proves that nothing else does</strong> is that these rows are
 * per MEMBER. Everywhere else on this API a household mate must see a household mate's rows,
 * because the ledger is shared; here they must not, and 404 — never 403 — is the refusal, so that
 * "somebody else's" and "does not exist" cannot be told apart.
 *
 * <p>The turns are created through the production {@link CoachChatService} with only the gateway
 * stubbed (see {@link CoachStubGateway}: the integration suite cannot reach a loopback stub through
 * the real egress policy without forking a second Spring context). Everything read back is read
 * over real HTTP through the real routes, against the real contract.
 */
class CoachIT extends AuthFlowSupport {

    private static final String ANSWER = "You spent the most on Groceries this month.";

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
        stub.json("/chat/completions",
            "{\"model\":\"" + CoachStubGateway.MODEL
                + "\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                + ANSWER + "\"}}]}");
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    @Test
    @DisplayName("a conversation is created, continued through its session id, and read back")
    void aConversationIsCreatedContinuedAndRead() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-life");
        CoachChatService coach = coach();

        CoachTurn first = coach.prepare(member.userId(), member.householdId(), null,
            ask("What did I spend the most on this month?"));
        ChatCompletionResponse answered = coach.answer(first);
        assertThat(answered.sessionId()).isEqualTo(first.conversationId());
        assertThat(answered.content()).isEqualTo(ANSWER);

        CoachTurn second = coach.prepare(member.userId(), member.householdId(),
            first.conversationId(), ask("And the month before?"));
        assertThat(second.conversationId())
            .as("a session id continues the conversation it names rather than starting a new one")
            .isEqualTo(first.conversationId());
        assertThat(second.prompt())
            .as("the earlier turns are replayed from the server's own store, decrypted")
            .anyMatch(message -> ANSWER.equals(message.content()))
            .anyMatch(message ->
                "What did I spend the most on this month?".equals(message.content()));
        assertThat(second.prompt().get(0).role())
            .as("the system turn is rebuilt fresh every time, never replayed from storage")
            .isEqualTo("system");
        assertThat(second.prompt().stream()
            .filter(message -> "system".equals(message.role())).count())
            .as("exactly one system turn, however long the conversation gets")
            .isOne();
        coach.answer(second);

        assertThat(gateway.sensitivities())
            .as("every coach call carries the household's ledger")
            .containsOnly(AiSensitivity.SENSITIVE);

        HttpResponse<String> history = get(
            appUrl("/v1/chat/history?sessionId=" + first.conversationId()),
            member.accessToken());
        assertThat(history.statusCode()).as(history.body()).isEqualTo(200);
        List<JsonNode> turns = Json.elements(Json.data(history.body()));
        assertThat(turns).hasSize(4);
        assertThat(turns.stream().map(turn -> turn.path("role").asString()).toList())
            .as("oldest first within a conversation, and never the SYSTEM turn")
            .containsExactly("USER", "ASSISTANT", "USER", "ASSISTANT");
        assertThat(turns.get(0).path("content").asString())
            .isEqualTo("What did I spend the most on this month?");
        assertThat(turns.get(1).path("model").asString()).isEqualTo(CoachStubGateway.MODEL);
        assertThat(turns.get(0).path("model").isNull())
            .as("a question came from nobody's model")
            .isTrue();
        assertThat(turns.get(0).path("sessionId").asString())
            .isEqualTo(first.conversationId().toString());
        assertThat(Json.parse(history.body()).path("meta").path("total").asInt())
            .as("history is paged with the existing PaginationMeta shape")
            .isEqualTo(4);
    }

    @Test
    @DisplayName("history spans every conversation when no session id is given")
    void historySpansEveryConversation() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-many");
        CoachChatService coach = coach();

        UUID older = answered(coach, member, null, "First question");
        UUID newer = answered(coach, member, null, "Second question");

        HttpResponse<String> history = get(appUrl("/v1/chat/history"), member.accessToken());
        assertThat(history.statusCode()).as(history.body()).isEqualTo(200);
        List<JsonNode> turns = Json.elements(Json.data(history.body()));
        assertThat(turns).hasSize(4);
        assertThat(turns.get(0).path("sessionId").asString())
            .as("most recently used conversation first, chronological within it")
            .isEqualTo(newer.toString());
        assertThat(turns.get(3).path("sessionId").asString()).isEqualTo(older.toString());
    }

    @Test
    @DisplayName("a household mate gets 404 for another member's conversation, never its messages")
    void aHouseholdMateIsRefused() {
        Session admin = loginBootstrapAdmin();
        Session mate = inviteAndRegister(admin, "coach-mate");
        CoachChatService coach = coach();

        UUID adminsConversation = answered(coach, admin, null, "My private question");

        HttpResponse<String> read = get(
            appUrl("/v1/chat/history?sessionId=" + adminsConversation), mate.accessToken());
        assertThat(read.statusCode())
            .as("these rows are per member; a mate's is 404, never 403 and never 200")
            .isEqualTo(404);
        assertThat(read.body())
            .as("a refusal never leaks the other member's question")
            .doesNotContain("My private question");

        HttpResponse<String> cleared = delete(
            appUrl("/v1/chat/history?sessionId=" + adminsConversation), null,
            "Authorization", "Bearer " + mate.accessToken());
        assertThat(cleared.statusCode()).isEqualTo(404);

        HttpResponse<String> mateHistory = get(appUrl("/v1/chat/history"), mate.accessToken());
        assertThat(Json.elements(Json.data(mateHistory.body())))
            .as("the mate's own history does not contain the admin's turns")
            .allSatisfy(turn -> assertThat(turn.path("sessionId").asString())
                .isNotEqualTo(adminsConversation.toString()));

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_message where conversation_id = ?", Long.class,
            adminsConversation))
            .as("a refused delete deletes nothing — SYSTEM, USER and ASSISTANT are all still "
                + "there, and only two of the three were ever returnable")
            .isEqualTo(3L);
    }

    @Test
    @DisplayName("clearing one conversation leaves the others; clearing all is idempotent")
    void clearingIsScopedAndIdempotent() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-clear");
        CoachChatService coach = coach();

        UUID kept = answered(coach, member, null, "Keep this one");
        UUID dropped = answered(coach, member, null, "Drop this one");

        HttpResponse<String> one = delete(appUrl("/v1/chat/history?sessionId=" + dropped), null,
            "Authorization", "Bearer " + member.accessToken());
        assertThat(one.statusCode()).as(one.body()).isEqualTo(200);
        assertThat(one.body()).isEqualTo("{\"success\":true}");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_conversation where id = ?", Long.class, dropped)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_message where conversation_id = ?", Long.class, dropped))
            .as("the messages go with the conversation, by the FK's ON DELETE CASCADE")
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_conversation where id = ?", Long.class, kept)).isOne();

        assertThat(delete(appUrl("/v1/chat/history"), null,
            "Authorization", "Bearer " + member.accessToken()).statusCode()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_conversation where user_id = ?", Long.class,
            member.userId())).isZero();
        assertThat(delete(appUrl("/v1/chat/history"), null,
            "Authorization", "Bearer " + member.accessToken()).statusCode())
            .as("clearing everything twice is a 200, not a 404: the alternative asks whether "
                + "somebody has used the coach")
            .isEqualTo(200);
    }

    @Test
    @DisplayName("suggestions follow the member's last question and cost no AI call")
    void suggestionsFollowTheLastQuestion() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-suggest");

        HttpResponse<String> before = get(appUrl("/v1/chat/suggestions"), member.accessToken());
        assertThat(before.statusCode()).as(before.body()).isEqualTo(200);
        List<JsonNode> defaults = Json.elements(Json.data(before.body()));
        assertThat(defaults).isNotEmpty();

        assertThat(stub.received())
            .as("the empty-state fetch made no model call at all")
            .isEmpty();

        answered(coach(), member, null, "Which envelope is over budget?");
        int callsBeforeSuggestions = stub.received().size();

        HttpResponse<String> after = get(appUrl("/v1/chat/suggestions"), member.accessToken());
        List<String> budgetSuggestions = Json.elements(Json.data(after.body())).stream()
            .map(JsonNode::asString)
            .toList();
        assertThat(budgetSuggestions)
            .as("the budget keyword list matched, so the budget suggestions came back")
            .isEqualTo(prompts.suggestions().budgetSuggestions());
        assertThat(stub.received())
            .as("suggestions are template text; making them cost a model call would make an "
                + "empty screen wait on somebody's endpoint")
            .hasSize(callsBeforeSuggestions);
    }

    @Test
    @DisplayName("an unknown or malformed session id is 404 and 400, never a new conversation")
    void unknownSessionIdsAreRefused() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-badsession");

        HttpResponse<String> unknown = get(
            appUrl("/v1/chat/history?sessionId=" + UUID.randomUUID()), member.accessToken());
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(Json.errorCode(unknown.body())).isEqualTo("not-found");

        HttpResponse<String> malformed = withoutRequestValidation(
            "sessionId is declared as a uuid, so no generated client can send this; the server "
                + "still has to answer 400 rather than 500",
            () -> get(appUrl("/v1/chat/history?sessionId=not-a-uuid"), member.accessToken()));
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(Json.errorCode(malformed.body())).isEqualTo("validation-error");
    }

    @Test
    @DisplayName("an empty sessionId is refused on both history routes, never read as 'all'")
    void anEmptySessionIdNeverMeansEveryConversation() {
        Session member = inviteAndRegister(loginBootstrapAdmin(), "coach-emptysession");
        UUID kept = answered(coach(), member, null, "Do not delete this");

        HttpResponse<String> cleared = withoutRequestValidation(
            "sessionId is declared as a uuid, so no generated client can send an empty one; the "
                + "server still has to refuse it rather than read it as every conversation",
            () -> delete(appUrl("/v1/chat/history?sessionId="), null,
                "Authorization", "Bearer " + member.accessToken()));
        assertThat(cleared.statusCode()).as(cleared.body()).isEqualTo(400);
        assertThat(Json.errorCode(cleared.body())).isEqualTo("validation-error");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from coach_conversation where id = ?", Long.class, kept))
            .as("a URL built from an unset client variable must not destroy the whole history")
            .isOne();

        HttpResponse<String> read = withoutRequestValidation(
            "the same empty value on the read side, where it would have returned every "
                + "conversation the member has instead of the one they named",
            () -> get(appUrl("/v1/chat/history?sessionId="), member.accessToken()));
        assertThat(read.statusCode()).as(read.body()).isEqualTo(400);
        assertThat(Json.errorCode(read.body())).isEqualTo("validation-error");
    }

    private CoachChatService coach() {
        return new CoachChatService(gateway, conversations, contextBuilder, prompts,
            coachProperties, users, clock);
    }

    private UUID answered(CoachChatService coach, Session member, UUID sessionId, String question) {
        CoachTurn turn =
            coach.prepare(member.userId(), member.householdId(), sessionId, ask(question));
        coach.answer(turn);
        return turn.conversationId();
    }

    private static ChatCompletionRequest ask(String question) {
        return new ChatCompletionRequest(List.of(new ChatTurnRequest("user", question)));
    }
}
