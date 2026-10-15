package finance.trove.ai.chat;

import finance.trove.ai.chat.dto.ChatCompletionRequest;
import finance.trove.ai.chat.dto.ChatCompletionResponse;
import finance.trove.ai.chat.dto.CoachMessageResponse;
import finance.trove.auth.jwt.TroveUserDetails;
import finance.trove.common.domain.PagedResult;
import finance.trove.common.dto.ApiResponse;
import finance.trove.common.dto.PaginationMeta;
import finance.trove.common.exception.ErrorResponseFactory;
import finance.trove.common.exception.ValidationException;
import finance.trove.common.observability.RequestPath;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The coach's four routes ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.11; collapse step 13).
 *
 * <h2>The caller is the signed principal, always</h2>
 * Every method takes {@code @AuthenticationPrincipal TroveUserDetails} and passes
 * {@code getUserId()} and {@code getHouseholdId()} down. No identity is ever taken from a path, a
 * body or a header: {@code SourceConventionTest} bans reading {@code X-User-Id} and
 * {@code ArchitectureTest} bans a {@code userId} field on a request DTO. {@code X-Session-Id} names
 * a conversation, not a person, and a conversation that is not the caller's is 404.
 *
 * <h2>404, never 403, and that includes a household mate</h2>
 * These rows are per member. Everywhere else in this application a household mate shares the
 * ledger; here they do not, and "somebody else's conversation" and "no such conversation" have to
 * be the same answer or the route becomes an oracle for whether an id is real.
 *
 * <h2>Two handlers for one path</h2>
 * {@code POST /v1/chat/completions} is served by two methods distinguished by {@code stream=true},
 * because one writes bytes to the response itself and the other returns an {@code ApiResponse} for
 * Jackson to render — one method cannot do both. Both are the same {@code (method, path)} pair to
 * {@code OpenApiRouteDriftIT} and to a client, which is why the contract has one operation with
 * two response content types.
 */
@RestController
@Validated
public class ChatController {

    /** The default page size for history — a screenful of turns, not a conversation. */
    public static final int DEFAULT_HISTORY_LIMIT = 50;

    /** Matches {@code LimitQuery.maximum} in {@code openapi/trove-core.yaml}. */
    public static final int MAX_HISTORY_LIMIT = 200;

    private static final String SESSION_HEADER = "X-Session-Id";

    private final CoachChatService coach;
    private final CoachConversationService conversations;
    private final SuggestionService suggestions;
    private final ErrorResponseFactory errors;
    private final ObjectMapper json;

    public ChatController(CoachChatService coach, CoachConversationService conversations,
                          SuggestionService suggestions, ErrorResponseFactory errors,
                          ObjectMapper json) {
        this.coach = coach;
        this.conversations = conversations;
        this.suggestions = suggestions;
        this.errors = errors;
        this.json = json;
    }

    /**
     * The streamed answer, written frame by frame on this thread.
     *
     * <p><strong>Everything that can fail before the first byte fails before the response is
     * committed.</strong> {@link CoachChatService#prepare} runs first and its exceptions — AI off
     * (409), a session that is not the caller's (404), a body that names no question (400) — reach
     * {@code GlobalExceptionHandler} with the response still uncommitted, so they come back as the
     * ordinary JSON envelope with a real status code, exactly as §4.11 requires. Only then is the
     * content type set and the buffer flushed, and from that point every failure is an in-band
     * frame.
     *
     * <p>The content type is set here rather than with a {@code produces} attribute: a
     * {@code produces} would constrain the producible media types of the
     * {@code @ExceptionHandler} that runs for the same request, turning those three status codes
     * into 406s.
     *
     * <p><strong>Written synchronously, and that is deliberate.</strong> Every asynchronous
     * alternative — {@code SseEmitter}, {@code ResponseBodyEmitter},
     * {@code StreamingResponseBody}, {@code DeferredResult} — puts the request into Servlet async
     * mode, and completing it dispatches back through the security chain, which is
     * {@code STATELESS} and therefore has no principal to reload: the dispatch is refused and the
     * client's stream is truncated mid-chunk. See {@link WriterFrameSink} for the whole reasoning
     * and for what a future change would need. The cost is one request thread per in-flight
     * generation, bounded by that member's own read timeout (capped at 120 s by
     * {@code AiTimeouts}) and by the {@code /api/v1/chat/**} limits V12 seeds.
     */
    @PostMapping(path = "/v1/chat/completions", params = "stream=true")
    public void completeStreaming(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestHeader(name = SESSION_HEADER, required = false) String sessionHeader,
        @Valid @RequestBody ChatCompletionRequest request,
        HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException {

        CoachTurn turn = coach.prepare(principal.getUserId(), principal.getHouseholdId(),
            sessionHeader(sessionHeader), request);

        httpResponse.setHeader(SESSION_HEADER, turn.conversationId().toString());
        httpResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // No Cache-Control is set here on purpose: the security chain's header writer already
        // puts `no-store` on every response, which is stronger than the `no-cache` an SSE
        // endpoint would normally add, and overwriting it here would quietly weaken it on the one
        // route whose body is a member's ledger in prose.
        // Commits the response — status, headers and content type — before the first frame, so a
        // client can branch on response.ok and the content type without waiting for any of the
        // answer.
        httpResponse.flushBuffer();

        coach.stream(turn, new WriterFrameSink(httpResponse.getWriter(), json,
            RequestPath.describe(httpRequest), errors.requestId(httpRequest)));
    }

    /** The whole answer at once. Endpoint failures are their own status codes here, not frames. */
    @PostMapping("/v1/chat/completions")
    public ApiResponse<ChatCompletionResponse> complete(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestHeader(name = SESSION_HEADER, required = false) String sessionHeader,
        @Valid @RequestBody ChatCompletionRequest request,
        HttpServletResponse httpResponse) {

        CoachTurn turn = coach.prepare(principal.getUserId(), principal.getHouseholdId(),
            sessionHeader(sessionHeader), request);
        httpResponse.setHeader(SESSION_HEADER, turn.conversationId().toString());
        return ApiResponse.ok(coach.answer(turn));
    }

    /**
     * Prompt starters. No AI call, no configured endpoint required: the screen has to render
     * before there is anything to suggest from.
     */
    @GetMapping("/v1/chat/suggestions")
    public ApiResponse<List<String>> suggestions(
        @AuthenticationPrincipal TroveUserDetails principal) {
        return ApiResponse.ok(suggestions.suggestFor(principal.getUserId()));
    }

    /** One page of the caller's own turns, decrypted for them and for nobody else. */
    @GetMapping("/v1/chat/history")
    public ApiResponse<List<CoachMessageResponse>> history(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam(required = false) String sessionId,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "" + DEFAULT_HISTORY_LIMIT)
        @Min(1) @Max(MAX_HISTORY_LIMIT) int limit) {

        PagedResult<CoachMessageResponse> result =
            conversations.page(principal.getUserId(), sessionParameter(sessionId), page, limit);
        return ApiResponse.paged(result.content(), PaginationMeta.from(result));
    }

    /**
     * Clears one conversation, or every one of the caller's.
     *
     * <p>Clearing everything answers {@code {"success":true}} even when there was nothing to clear;
     * clearing one names an id, so an id that is not the caller's is 404. Only an <em>absent</em>
     * {@code sessionId} means everything: a present but empty one is a 400, because this is the
     * route where reading the two the same way loses a member's whole history
     * (see {@link #sessionParameter}).</p>
     */
    @DeleteMapping("/v1/chat/history")
    public ApiResponse<Void> deleteHistory(
        @AuthenticationPrincipal TroveUserDetails principal,
        @RequestParam(required = false) String sessionId) {
        conversations.clear(principal.getUserId(), sessionParameter(sessionId));
        return ApiResponse.ok(null);
    }

    /**
     * The {@code X-Session-Id} header as a conversation id.
     *
     * <p>Parsed rather than bound so that a malformed value is a 400 {@code validation-error}
     * naming the header, instead of the 500 a failed converter on a header parameter produces. An
     * empty header is read as an absent one — it starts a conversation, which is what a client
     * that has no session yet wants and costs nothing if it was a template that did not fill.
     */
    static UUID sessionHeader(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return parse(SESSION_HEADER, header);
    }

    /**
     * The {@code sessionId} query parameter as a conversation id: null only when the parameter is
     * genuinely absent.
     *
     * <p><strong>Bound as a String on purpose.</strong> Spring's {@code StringToUUIDConverter}
     * answers null for a blank source, so {@code ?sessionId=} bound to a {@code UUID} is
     * indistinguishable from no parameter at all — and on {@code DELETE /v1/chat/history} "no
     * parameter at all" means <em>delete every conversation this member has</em>. A client that
     * builds its URL from an unset variable (`?sessionId=${sessionId}`, the ordinary shape of the
     * fetch readers 20b/21b still owe) would silently destroy the member's whole coach history.
     * Present-but-empty is therefore a 400, and only an absent parameter means "all of them".
     */
    static UUID sessionParameter(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            throw new ValidationException(
                "sessionId must be a UUID; omit the parameter entirely to mean every conversation");
        }
        return parse("sessionId", raw);
    }

    private static UUID parse(String name, String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ValidationException(name + " must be a UUID", e);
        }
    }
}
