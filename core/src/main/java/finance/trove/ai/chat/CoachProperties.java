package finance.trove.ai.chat;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The coach's runtime numbers — the ones an operator might tune, as opposed to the prompt text in
 * {@code prompts/coach.yml} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6, §5.7).
 *
 * <p><strong>Every value carries its own default here and nothing is added to
 * {@code application.yml}.</strong> Collapse steps 11, 13, 14 and 16 run in parallel worktrees and
 * no step of this wave owns that file, so a property added to it is a merge conflict at best and a
 * silently lost edit at worst. A record bound by {@code @ConfigurationPropertiesScan} with
 * defaults in its canonical constructor needs no yml entry to work and picks one up the moment an
 * operator adds it — the same shape {@code AiCategorisationProperties} (step 17) and
 * {@code SubscriptionDetectionProperties} use. The keys are named in this step's merge notes so
 * they can be promoted into the shipped configuration later.
 *
 * <p>There is deliberately no stream timeout here. The SSE body is written synchronously on the
 * request thread ({@code WriterFrameSink} explains why), so the deadline that bounds it is already
 * the member's own {@code readTimeoutMs}, capped at 120 s by {@code AiTimeouts} — a second number
 * beside it could only disagree with it.
 *
 * @param contextDeadline     how long {@link CoachContextBuilder} waits for all four ledger reads
 *                            together. Five seconds is §6's figure. It is a deadline on the whole
 *                            fan-out, not per section: a member waiting on an answer does not care
 *                            which query was slow, and four five-second budgets is a twenty-second
 *                            wait before the first token. A section that misses it is reported as
 *                            unavailable, never as zero
 * @param maxAnswerCharacters the longest answer that is stored. The answer is untrusted text from
 *                            somebody else's server (readiness gap row 44) and a model that
 *                            decides to write for ever must not be able to fill a column with it.
 *                            Sixteen thousand characters is roughly four thousand tokens of prose
 *                            — far longer than any answer this prompt asks for, short enough that
 *                            a runaway generation is truncated rather than stored. A truncated
 *                            answer is still persisted: it is what the member saw
 */
@ConfigurationProperties("trove.ai.coach")
public record CoachProperties(Duration contextDeadline, Integer maxAnswerCharacters) {

    /** §6's figure for the whole context fan-out. */
    public static final Duration DEFAULT_CONTEXT_DEADLINE = Duration.ofSeconds(5);

    /** The stored-answer cap gap row 44 asks for. */
    public static final int DEFAULT_MAX_ANSWER_CHARACTERS = 16_000;

    public CoachProperties {
        contextDeadline = contextDeadline == null ? DEFAULT_CONTEXT_DEADLINE : contextDeadline;
        maxAnswerCharacters = maxAnswerCharacters == null
            ? DEFAULT_MAX_ANSWER_CHARACTERS : maxAnswerCharacters;

        if (contextDeadline.isNegative() || contextDeadline.isZero()) {
            throw new IllegalArgumentException(
                "trove.ai.coach.context-deadline must be positive; zero would leave every context "
                    + "section unavailable on every question. Got " + contextDeadline);
        }
        if (maxAnswerCharacters < 1) {
            throw new IllegalArgumentException(
                "trove.ai.coach.max-answer-characters must be at least 1, got "
                    + maxAnswerCharacters);
        }
    }
}
