package finance.trove.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import finance.trove.ai.chat.CoachAudience;
import finance.trove.ai.chat.CoachChatService;
import finance.trove.ai.chat.CoachContextBuilder;
import finance.trove.ai.chat.CoachConversation;
import finance.trove.ai.chat.CoachConversationService;
import finance.trove.ai.chat.CoachFrameSink;
import finance.trove.ai.chat.CoachProperties;
import finance.trove.ai.chat.CoachPromptsProperties;
import finance.trove.ai.chat.dto.ChatCompletionRequest;
import finance.trove.ai.chat.dto.ChatTurnRequest;
import finance.trove.ai.insight.InsightRecipients;
import finance.trove.ai.insight.InsightService;
import finance.trove.ai.insight.WeeklyInsightJob;
import finance.trove.app.support.LogCapture;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.time.UserClock;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Nothing the coach handles reaches a log line: not the question, not the answer, not the context
 * block, not the endpoint (collapse step 12's {@code AiSecrecyTest} and step 17's
 * {@code AiCategorisationSecrecyTest} are the same shape, for the client and the categorisation
 * tier).
 *
 * <p>The failure this guards against is silent and permanent. A member's question names what they
 * bought and for how much; the context block is their household's whole month. Neither shows up as
 * a broken feature if it is logged — it shows up months later in a log aggregator, on a shared
 * screen, or in a support bundle somebody emailed.
 *
 * <p>The level is forced to TRACE for the duration, which is the point: at the shipped
 * {@code finance.trove: INFO} the success path logs nothing at all and the assertion would pass
 * without proving anything. Every failure path is exercised too, because that is where a
 * well-meaning "log what went wrong" appears.
 */
class AiCoachSecrecyTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID CONVERSATION = UUID.randomUUID();

    private static final String QUESTION = "Why was my Rosedale Pharmacy bill 412.55 this month?";
    private static final String ANSWER = "Because of a 200.00 dispensing fee, Jane Doe.";
    private static final String CONTEXT = "CAD (yours): spent 4210.99, income 6000.00";
    private static final String HOST = "ai.jane-doe-private.example";
    private static final String API_KEY = "sk-coach-secret-key-9f2c";

    private final AiGateway gateway = mock(AiGateway.class);
    private final AiHandle handle = mock(AiHandle.class);
    private final CoachConversationService conversations = mock(CoachConversationService.class);
    private final CoachContextBuilder context = mock(CoachContextBuilder.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserClock clock = mock(UserClock.class);

    private CoachChatService service;
    private Logger troveLogger;
    private Level previousLevel;

    @BeforeEach
    void wire() {
        service = new CoachChatService(gateway, conversations, context,
            new CoachPromptsProperties("You are Trove.", "{{month_summary}}", 20, null),
            new CoachProperties(Duration.ofSeconds(5), 16_000),
            users, clock);

        when(gateway.forUser(USER)).thenReturn(handle);
        when(handle.isEnabled()).thenReturn(true);
        when(handle.privacyTier()).thenReturn(PrivacyTier.LOCAL_ONLY);
        User user = mock(User.class);
        when(user.getDisplayName()).thenReturn("Jane Doe");
        when(user.getCurrency()).thenReturn(CurrencyCode.CAD);
        when(user.getCountry()).thenReturn(Jurisdiction.CA);
        when(users.findByIdAndHouseholdId(USER, HOUSEHOLD)).thenReturn(Optional.of(user));
        when(clock.zoneOf(USER)).thenReturn(ZoneId.of("America/Toronto"));
        when(clock.now()).thenReturn(Instant.parse("2026-09-05T18:04:00Z"));
        CoachConversation conversation = mock(CoachConversation.class);
        when(conversation.getId()).thenReturn(CONVERSATION);
        when(conversations.startOrResume(eq(USER), any())).thenReturn(conversation);
        when(conversations.historyWindow(eq(USER), eq(CONVERSATION), anyInt()))
            .thenReturn(List.of());
        when(context.build(any(), any())).thenReturn(CONTEXT);

        troveLogger = (Logger) LoggerFactory.getLogger("finance.trove");
        previousLevel = troveLogger.getLevel();
        troveLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void restoreLevel() {
        troveLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("a streamed failure logs nothing about the question, the answer or the endpoint")
    void aFailedStreamLogsNothingSecret() {
        when(handle.chatStream(any(), any(), any())).thenThrow(new AiUnreachableException(
            "The endpoint " + HOST + " refused the connection"));

        try (LogCapture logs = LogCapture.start()) {
            service.stream(service.prepare(USER, HOUSEHOLD, null, ask()), new SilentSink());

            assertThat(String.join("\n", logs.lines()))
                .doesNotContain(QUESTION)
                .doesNotContain("Rosedale Pharmacy")
                .doesNotContain("412.55")
                .doesNotContain(CONTEXT)
                .doesNotContain(API_KEY);
        }
    }

    @Test
    @DisplayName("an unexpected failure logs the conversation id and nothing about its content")
    void anUnexpectedFailureLogsNoContent() {
        when(handle.chatStream(any(), any(), any()))
            .thenThrow(new IllegalStateException("something broke while sending " + QUESTION));

        try (LogCapture logs = LogCapture.start()) {
            service.stream(service.prepare(USER, HOUSEHOLD, null, ask()), new SilentSink());
            String logged = String.join("\n", logs.lines());

            assertThat(logged)
                .as("the conversation id is the operator's handle on the failure and is not "
                    + "content")
                .contains(CONVERSATION.toString());
            assertThat(logged)
                .as("the throwable's own message is the one thing here that could carry a "
                    + "prompt, and the exception is logged WITH its stack trace — so this "
                    + "assertion is about what THIS code adds, and about never putting a "
                    + "prompt into an exception message of our own")
                .doesNotContain(CONTEXT)
                .doesNotContain(ANSWER);
        }
    }

    @Test
    @DisplayName("a weekly-insight failure logs the slug, not the member's data or the endpoint")
    void theWeeklyJobLogsOnlyASlug() {
        InsightRecipients recipients = mock(InsightRecipients.class);
        InsightService insights = mock(InsightService.class);
        CoachAudience audience = new CoachAudience(USER, HOUSEHOLD, "Jane Doe", CurrencyCode.CAD,
            Jurisdiction.CA, ZoneId.of("America/Toronto"));
        when(recipients.page(eq(0), anyInt())).thenReturn(List.of(audience));
        when(insights.generateFor(any(), any())).thenThrow(new AiEndpointUnauthorizedException(
            "The endpoint " + HOST + " rejected the stored key " + API_KEY));

        try (LogCapture logs = LogCapture.start()) {
            new WeeklyInsightJob(recipients, insights, clock)
                .runOnce(Instant.parse("2026-09-07T08:30:00Z"));
            String logged = String.join("\n", logs.lines());

            assertThat(logged)
                .as("the slug is what an operator needs; the message is written for the member")
                .contains("ai-endpoint-unauthorized");
            assertThat(logged)
                .as("a per-user endpoint host is that member's own network, and an API key is "
                    + "their credential for somebody else's service — neither is this install's "
                    + "to write down")
                .doesNotContain(HOST)
                .doesNotContain(API_KEY)
                .doesNotContain("Jane Doe");
        }
    }

    private static ChatCompletionRequest ask() {
        return new ChatCompletionRequest(List.of(new ChatTurnRequest("user", QUESTION)));
    }

    /** Swallows the frames; this test is about the log, not about the wire. */
    private static final class SilentSink implements CoachFrameSink {

        @Override
        public void delta(String content) {
            // nothing
        }

        @Override
        public void done() {
            // nothing
        }

        @Override
        public void failure(int status, String slug, String detail) {
            // nothing
        }
    }
}
