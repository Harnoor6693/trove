package finance.trove.ai.insight;

import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import finance.trove.ai.AiSensitivity;
import finance.trove.ai.chat.CoachAnswer;
import finance.trove.ai.chat.CoachAudience;
import finance.trove.ai.chat.CoachContextBuilder;
import finance.trove.ai.chat.CoachProperties;
import finance.trove.ai.chat.CoachPromptsProperties;
import finance.trove.ai.dto.AiCompletion;
import finance.trove.ai.dto.AiMessage;
import finance.trove.ai.insight.dto.InsightResponse;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.domain.PagedResult;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads the weekly insight ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6;
 * {@code docs/recovery/07-READINESS-GAPS.md} row 15; inventory model-manager D5).
 *
 * <h2>The week belongs to the recipient, not to the container</h2>
 * The job's trigger is a UTC cron because the container clock is UTC by policy. The week a note
 * describes is the recipient's own: {@link #weekStartFor} resolves "the Monday before last" in the
 * member's {@code users.timezone}, so two members of one household in America/Vancouver and
 * America/Toronto can get different {@code weekStart} values from the same run — which is the
 * point, and is what a snapshot of {@code LocalDate.now()} on the container could never produce.
 *
 * <h2>The jurisdiction is theirs too</h2>
 * The source called its generator with the literal {@code "CA"} for every user on earth, and its
 * own unit test asserted that literal as correct — the bug encoded as a passing test (inventory
 * D5). It comes from {@code users.country} here, through {@link CoachAudience}, which has no way to
 * be constructed without one.
 *
 * <h2>Sensitive, always</h2>
 * A weekly note is written from the household's ledger, so the call is
 * {@link AiSensitivity#SENSITIVE} like every other coach call. A recipient whose privacy tier is
 * {@code CLOUD_OK} is refused by the gateway before a connection opens; the job records that and
 * moves on to the next member rather than treating it as an outage.
 *
 * <h2>The note is untrusted output</h2>
 * It is stored encrypted and displayed. Nothing parses it, and {@link CoachAnswer} strips control
 * characters and caps its length exactly as it does a chat answer.
 */
@Service
public class InsightService {

    /** How the job asks for a note. Plain text; nothing in the answer is ever parsed. */
    private static final String INSTRUCTION_TEMPLATE = """
        Write this member's weekly money note for the week of %s to %s, in their timezone %s.
        Their figures are in %s and they file in %s.
        Three or four short sentences: what changed against the week before, one thing worth
        noticing, and one concrete suggestion they could act on this week.
        Use only the figures in the context above. If a section says it is unavailable, say that
        you could not see it rather than guessing a number.
        Do not recommend any financial product, stock or investment.
        """;

    private final AiGateway gateway;
    private final CoachContextBuilder context;
    private final CoachPromptsProperties prompts;
    private final CoachProperties properties;
    private final CoachInsightRepository insights;
    private final FieldEncryptionService encryption;

    public InsightService(AiGateway gateway, CoachContextBuilder context,
                          CoachPromptsProperties prompts, CoachProperties properties,
                          CoachInsightRepository insights, FieldEncryptionService encryption) {
        this.gateway = gateway;
        this.context = context;
        this.prompts = prompts;
        this.properties = properties;
        this.insights = insights;
        this.encryption = encryption;
    }

    /**
     * The Monday of the week a run at {@code runAt} should describe, for this recipient.
     *
     * <p>The week that has just ended, not the one in progress: a note about three days of a week
     * is a note about nothing. {@code previousOrSame(MONDAY)} on the recipient's own local date,
     * minus a week.
     */
    public static LocalDate weekStartFor(CoachAudience audience, Instant runAt) {
        LocalDate localToday = runAt.atZone(audience.zone()).toLocalDate();
        return localToday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    /**
     * Generates one member's note and stores it, unless they already have one for that week.
     *
     * @return true when a row was written; false when the member already had one, which is the
     *         normal outcome of a second run and costs nothing
     * @throws finance.trove.ai.AiException the recipient's own endpoint refused or failed. The
     *         caller records it against that member and carries on to the next
     */
    public boolean generateFor(CoachAudience audience, Instant runAt) {
        LocalDate weekStart = weekStartFor(audience, runAt);
        LocalDate weekEnd = weekStart.plusDays(6);

        AiHandle handle = gateway.forUser(audience.userId());
        if (!handle.isEnabled()) {
            return false;
        }

        String instruction = INSTRUCTION_TEMPLATE.formatted(weekStart, weekEnd,
            audience.zone().getId(), audience.currency().name(), audience.country().name());
        List<AiMessage> prompt = List.of(
            AiMessage.system(prompts.systemPrompt() + "\n\n" + context.build(audience, runAt)),
            AiMessage.user(instruction));

        AiCompletion completion = handle.chat(prompt, AiSensitivity.SENSITIVE);
        String note = CoachAnswer.sanitise(completion.content(), properties.maxAnswerCharacters());
        if (note.isBlank()) {
            return false;
        }
        return insights.insertIfAbsent(audience.userId(), weekStart, encryption.encrypt(note)) > 0;
    }

    /** One page of this member's notes, newest first, decrypted for them alone. */
    @Transactional(readOnly = true)
    public PagedResult<InsightResponse> page(UUID userId, int page, int limit) {
        Page<CoachInsight> found = insights.pageForUser(userId, PageRequest.of(page, limit));
        List<InsightResponse> content = found.getContent().stream()
            .map(insight -> new InsightResponse(insight.getId(), insight.getWeekStart(),
                encryption.decrypt(insight.getContentEncrypted()), insight.isDelivered(),
                insight.getCreatedAt(), insight.getDeliveredAt()))
            .toList();
        return new PagedResult<>(content, found.getTotalElements(), page, limit);
    }
}
