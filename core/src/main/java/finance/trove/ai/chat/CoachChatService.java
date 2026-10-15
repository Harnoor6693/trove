package finance.trove.ai.chat;

import finance.trove.ai.AiDisabledException;
import finance.trove.ai.AiException;
import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.AiPolicyBlockedException;
import finance.trove.ai.AiSensitivity;
import finance.trove.ai.AiStreamCall;
import finance.trove.ai.chat.dto.ChatCompletionRequest;
import finance.trove.ai.chat.dto.ChatCompletionResponse;
import finance.trove.ai.chat.dto.ChatTurnRequest;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.common.exception.ErrorResponseFactory;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.net.OutboundUrlPolicyException;
import finance.trove.common.time.UserClock;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The coach itself: one question in, one answer out, streaming or not
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11, §6; collapse step 13).
 *
 * <h2>Everything cheap happens before the response is committed</h2>
 * {@link #prepare} runs on the request thread and does every check that can be made without
 * opening a connection: the member's AI is on ({@code ai-disabled}, 409), their privacy tier
 * permits a payload built from the household's ledger ({@code ai-policy-blocked}, 403), the
 * session id is theirs ({@code not-found}, 404), the body names a question
 * ({@code validation-error}, 400). Only then does a stream open. That ordering is the difference
 * between a client that can read {@code response.ok} and branch, and one that has to parse an
 * error out of a 200.
 *
 * <h2>Every payload is SENSITIVE, without exception</h2>
 * The context block IS the household's ledger — balances, envelope names, run rates — and the
 * question is about it. {@link AiSensitivity#SENSITIVE} is passed on every call this class makes,
 * which is why a member whose endpoint is somebody else's server ({@code PrivacyTier.CLOUD_OK}) is
 * refused rather than quietly served. That refusal is a feature: it is the member's own setting
 * being honoured, and downgrading it to {@code ORDINARY} to make a test pass would send a
 * household's finances to a third party.
 *
 * <h2>The question is written before the call; the answer after it</h2>
 * A dropped connection, a timeout or a model that dies mid-sentence therefore loses the answer and
 * never the question. A partial answer is still written — it is what the member watched arrive.
 *
 * <h2>The answer is text, and only text</h2>
 * Nothing here parses it. No id, no category, no URL this server fetches, no tool call and no
 * command is ever taken from an answer; this step wires no tool calling or function calling of any
 * kind. {@link CoachAnswer} strips control characters and caps the length, and that is the whole
 * of what is done to it (readiness gap row 44).
 */
@Service
public class CoachChatService {

    /**
     * The slug the in-band frame carries when the answer outran
     * {@link CoachProperties#maxAnswerCharacters}. 413, and declared in
     * {@code openapi/trove-core.yaml}'s slug enum as an in-band-only one: no HTTP response of this
     * API carries it, because the non-streaming path caps silently inside a 200 and only a stream
     * that has already been committed has to say why it stopped.
     */
    private static final String ANSWER_TOO_LONG = "answer-too-long";

    private static final Logger LOG = LoggerFactory.getLogger(CoachChatService.class);

    private final AiGateway gateway;
    private final CoachConversationService conversations;
    private final CoachContextBuilder context;
    private final CoachPromptsProperties prompts;
    private final CoachProperties properties;
    private final UserRepository users;
    private final UserClock clock;

    public CoachChatService(AiGateway gateway, CoachConversationService conversations,
                            CoachContextBuilder context, CoachPromptsProperties prompts,
                            CoachProperties properties, UserRepository users, UserClock clock) {
        this.gateway = gateway;
        this.conversations = conversations;
        this.context = context;
        this.prompts = prompts;
        this.properties = properties;
        this.users = users;
        this.clock = clock;
    }

    /**
     * Resolves the caller, their conversation and the exact prompt, and writes their question.
     *
     * @param sessionId the {@code X-Session-Id} the client sent, or null to start a conversation
     * @throws AiDisabledException      the caller has no AI configured, or has turned theirs off
     * @throws AiPolicyBlockedException their own privacy tier forbids sending their ledger
     * @throws ResourceNotFoundException {@code sessionId} is not theirs
     * @throws ValidationException      the body names no question
     */
    public CoachTurn prepare(UUID userId, UUID householdId, UUID sessionId,
                             ChatCompletionRequest request) {
        String question = questionOf(request);
        AiHandle handle = gateway.forUser(userId);
        if (!handle.isEnabled()) {
            throw new AiDisabledException(
                "AI is not configured for this account. Add an endpoint and a model in Settings "
                    + "to use the coach.");
        }
        if (!handle.privacyTier().permits(AiSensitivity.SENSITIVE)) {
            // Checked here as well as inside the gateway, and it is not a duplicate: refusing
            // before the conversation is touched means a member on CLOUD_OK gets an ordinary 403
            // they can act on rather than a 200 whose only content is an in-band error frame.
            throw new AiPolicyBlockedException(
                "Your privacy setting is " + handle.privacyTier() + ", which does not allow your "
                    + "household's financial data to be sent to your AI endpoint. The coach reads "
                    + "your ledger to answer, so it needs LOCAL_ONLY.");
        }

        CoachAudience audience = audienceOf(userId, householdId);
        boolean existing = sessionId != null;
        CoachConversation conversation = conversations.startOrResume(userId, sessionId);
        UUID conversationId = conversation.getId();

        String systemTurn = systemTurn(audience);
        if (!existing) {
            // Written once, on the conversation it opened: it records what the model was actually
            // told, which for a feature whose output is untrusted is worth having. It is never
            // returned by any route and never replayed into a later prompt — a fresh block is
            // built every turn, because last week's balances presented as today's would be worse
            // than no context at all.
            conversations.appendSystemMessage(userId, conversationId, systemTurn);
        }

        List<AiMessage> prompt = new ArrayList<>();
        prompt.add(AiMessage.system(systemTurn));
        prompt.addAll(conversations.historyWindow(userId, conversationId,
            prompts.maxHistoryMessages()));
        prompt.add(AiMessage.user(question));

        conversations.appendUserMessage(userId, conversationId, question);
        return new CoachTurn(audience, conversationId, handle, prompt);
    }

    /** The non-streaming answer. Endpoint failures surface as their own status codes. */
    public ChatCompletionResponse answer(CoachTurn turn) {
        AiCompletion completion = turn.handle().chat(turn.prompt(), AiSensitivity.SENSITIVE);
        String content =
            CoachAnswer.sanitise(completion.content(), properties.maxAnswerCharacters());
        conversations.appendAssistantMessage(turn.audience().userId(), turn.conversationId(),
            content, completion.model());
        return new ChatCompletionResponse(turn.conversationId(), CoachRole.ASSISTANT, content,
            completion.model(), Instant.now());
    }

    /**
     * The streaming answer: forwards every delta to {@code sink} as it arrives, then either the
     * terminator or one in-band error frame.
     *
     * <p>Blocking, and called from a thread the caller owns — this method does not decide where it
     * runs. It never throws: once the response is open the only way to tell the client anything is
     * a frame, and an exception escaping here would reach {@code GlobalExceptionHandler} on a
     * response that is already committed at 200 — a truncated body with no explanation in it.
     */
    public void stream(CoachTurn turn, CoachFrameSink sink) {
        StringBuilder assembled = new StringBuilder();
        int cap = properties.maxAnswerCharacters();
        boolean[] overran = {false};
        boolean[] reported = {false};

        try {
            AiCompletion completion = AiStreamCall.stream(turn.handle(), turn.prompt(),
                AiSensitivity.SENSITIVE, new AiStreamCall.Sink() {
                    @Override
                    public void onDelta(String content) {
                        if (overran[0]) {
                            return;
                        }
                        String clean = CoachAnswer.sanitiseDelta(content);
                        if (clean.isEmpty()) {
                            return;
                        }
                        if (assembled.length() + clean.length() > cap) {
                            // The cap is reached mid-generation. Forwarding stops here, what
                            // arrived is kept, and the stream ends with the in-band error frame
                            // below rather than with [DONE] — a truncated answer the member can
                            // see is honest, and pretending it finished would not be.
                            overran[0] = true;
                            return;
                        }
                        assembled.append(clean);
                        sink.delta(clean);
                    }

                    @Override
                    public void onDone() {
                        // Nothing. The answer is persisted once, below, from the assembled text,
                        // so that the overrun case and the ordinary case take the same path.
                    }

                    @Override
                    public void onError(AiException error) {
                        reported[0] = true;
                        persist(turn, assembled.toString(), null);
                        report(sink, error.getStatus().value(), error.getSlug());
                    }
                });

            persist(turn, assembled.toString(), completion.model());
            if (overran[0]) {
                // Not one of the ai-* slugs: nothing about the endpoint failed. It answered, and
                // this server stopped storing — so the slug says that, because the slug is the
                // half a client branches on and "ai-unreachable" would send the member to check
                // an endpoint that is working.
                report(sink, HttpStatus.CONTENT_TOO_LARGE.value(), ANSWER_TOO_LONG);
            } else {
                sink.done();
            }
        } catch (AiException e) {
            // The client calls onError and then throws the same exception, so the frame is
            // normally already out; a failure raised before the stream began reaches here
            // without one.
            if (!reported[0]) {
                persist(turn, assembled.toString(), null);
                report(sink, e.getStatus().value(), e.getSlug());
            }
        } catch (OutboundUrlPolicyException e) {
            // Not an AiException - it is a TroveException from the egress policy, which
            // DefaultAiGateway re-runs on every call, so a saved endpoint that stopped passing
            // (the install turned TROVE_EGRESS_ALLOW_PRIVATE off, the member withdrew
            // allowPrivateEndpoint, the name started resolving somewhere blocked) raises it here.
            // Falling through to the generic branch below would tell a member whose own settings
            // they can fix that something went wrong on our side.
            if (!reported[0]) {
                persist(turn, assembled.toString(), null);
                report(sink, e.getStatus().value(), e.getSlug());
            }
        } catch (RuntimeException e) {
            LOG.error("Coach stream failed for conversation {}", turn.conversationId(), e);
            if (!reported[0]) {
                persist(turn, assembled.toString(), null);
                sink.failure(HttpStatus.INTERNAL_SERVER_ERROR.value(), "internal-server-error",
                    ErrorResponseFactory.INTERNAL_DETAIL);
            }
        }
    }

    /**
     * Turns a failure into one in-band frame, by the status and slug it would have carried had
     * it not been streamed.
     *
     * <p>Status and slug rather than an exception, because not every reason a stream ends early is
     * one: the egress policy's {@code OutboundUrlPolicyException} is a {@code TroveException} and
     * the answer cap is no exception at all, and all three have to reach the member as the same
     * shape of frame with a slug that says what actually happened.
     *
     * <p><strong>The sentence comes from a fixed table, never from the exception.</strong>
     * {@code AiException}'s own messages are allowed to name the endpoint host — step 12 says so
     * explicitly, because on the settings screen the member needs to know which endpoint is wrong.
     * A streamed frame is a different place: it is rendered inside a conversation, which is the
     * thing a member screenshots and pastes into a support thread. Choosing the words by slug
     * makes it structural rather than careful — no stack trace, no prompt, no endpoint host and no
     * upstream response body can reach a frame, whatever a future exception message decides to
     * say. The host is still one request away, on {@code POST /v1/ai/settings/test}, which is
     * where an endpoint is configured.
     */
    private static void report(CoachFrameSink sink, int status, String slug) {
        sink.failure(status, slug, detailFor(slug));
    }

    private static String detailFor(String slug) {
        return switch (slug) {
            case "ai-timeout" -> "Your AI endpoint did not answer in time. A local model on "
                + "modest hardware may need a longer read timeout in Settings.";
            case "ai-unreachable" -> "Your AI endpoint could not be reached, or answered with "
                + "something this server cannot use. Check it in Settings.";
            case "ai-endpoint-unauthorized" -> "Your AI endpoint rejected the stored API key. "
                + "Save a new one in Settings.";
            case "ai-model-missing" -> "Your AI endpoint does not serve the chat model your "
                + "settings name. Pick one from the list in Settings.";
            case "ai-policy-blocked" -> "Your privacy setting does not allow your household's "
                + "financial data to be sent to this endpoint. The coach needs LOCAL_ONLY.";
            case "ai-disabled" -> "AI is not configured for this account. Add an endpoint and a "
                + "model in Settings to use the coach.";
            case "outbound-url-rejected" -> "Your saved AI endpoint is no longer one this server "
                + "will connect to. Check it in Settings.";
            case ANSWER_TOO_LONG -> "Your AI endpoint kept writing past the length this server "
                + "stores. What arrived has been saved.";
            default -> "The call to your AI endpoint failed. Check it in Settings and try again.";
        };
    }

    private void persist(CoachTurn turn, String content, String model) {
        try {
            conversations.appendAssistantMessage(turn.audience().userId(), turn.conversationId(),
                content, model);
        } catch (RuntimeException e) {
            // swallow-ok: losing the answer row must not also lose the frame that tells the member
            // what happened. The question is already committed and the failure is logged.
            LOG.error("Could not store the coach answer for conversation {}",
                turn.conversationId(), e);
        }
    }

    /** The system prompt plus this member's live ledger context. */
    private String systemTurn(CoachAudience audience) {
        return prompts.systemPrompt() + "\n\n" + context.build(audience, clock.now());
    }

    /**
     * The caller's own {@code users} row, scoped by the household on their signed token. Never a
     * body field, never a header: {@code ArchitectureTest} and {@code SourceConventionTest} both
     * exist to keep it that way.
     */
    private CoachAudience audienceOf(UUID userId, UUID householdId) {
        User user = users.findByIdAndHouseholdId(userId, householdId)
            .orElseThrow(() -> new ResourceNotFoundException("No such member in this household"));
        return new CoachAudience(userId, householdId, user.getDisplayName(), user.getCurrency(),
            user.getCountry(), clock.zoneOf(userId));
    }

    /**
     * The question: the last turn, which must be a {@code user} one.
     *
     * <p>A body whose last turn is an {@code assistant} one is refused rather than quietly
     * answered, because the only reason to send one is to have the model treat it as something it
     * said.
     */
    private static String questionOf(ChatCompletionRequest request) {
        ChatTurnRequest last = request == null ? null : request.lastTurn();
        if (last == null) {
            throw new ValidationException("messages must carry at least one turn");
        }
        if (CoachRole.fromWireName(last.role()) != CoachRole.USER) {
            throw new ValidationException(
                "the last entry of messages must be the question, with role 'user'");
        }
        String question = last.content() == null ? "" : last.content().trim();
        if (question.isEmpty()) {
            throw new ValidationException("content must not be blank");
        }
        return question;
    }
}
