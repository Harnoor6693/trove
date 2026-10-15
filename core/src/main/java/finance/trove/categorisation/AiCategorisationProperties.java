package finance.trove.categorisation;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every number the AI categorisation tier can be tuned by, in one place
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D4; collapse step 17).
 *
 * <p><strong>Every value carries its own default here, and nothing is added to
 * {@code application.yml}.</strong> Collapse steps 11, 15 and 17 run in parallel worktrees and no
 * step of this wave owns that file, so a property added to it is a merge conflict at best and a
 * silently lost edit at worst. A record bound by {@code @ConfigurationPropertiesScan} with
 * defaults in its canonical constructor needs no yml entry to work and picks one up the moment an
 * operator adds it — the same shape {@code PurgeJobs.reloadRateLimits} uses for
 * {@code trove.rate-limit.reload-interval} and {@code SubscriptionDetectionProperties} uses for
 * {@code trove.subscriptions.*}. The keys are named in this step's merge notes so an operator can
 * promote them into the shipped configuration later.
 *
 * <p>Defaults, and why each is what it is:
 * <ul>
 *   <li>{@code trove.ai.categorisation.min-confidence} — {@code 0.70}, the number the old service
 *       applied at every tier ({@code CategorisationService.java:159} and {@code :168}). Kept, so
 *       that the tier that costs money, time and privacy is no more eager than the free rule tiers
 *       were. Below it the household is better served by {@code Uncategorised}: a member corrects
 *       it once and the correction is learned as a {@link MappingSource#USER} row, which is a
 *       better mapping than a guess the model itself was unsure of.</li>
 *   <li>{@code trove.ai.categorisation.max-calls-per-batch} — {@code 25}. One AI call per
 *       uncategorised transaction is a five-hundred-call bill for one year of imported CSV, so
 *       the bound is explicit and per invocation. Twenty-five is chosen against what an import
 *       actually looks like: a year of statements is hundreds of rows but only a few dozen
 *       distinct merchants, and the write-back means each merchant is paid for exactly once ever.
 *       At a second or two per call it bounds one import's AI time to about a minute and its cost
 *       to twenty-five calls, and an import with more new merchants than that converges over the
 *       next few syncs instead of billing all of them at once. It is a default, not a ceiling —
 *       {@link BatchCategoriser} takes the cap as a parameter.</li>
 *   <li>{@code trove.ai.categorisation.max-answer-characters} — {@code 2000}. The length cap gap
 *       row 44 asks for, applied <em>before</em> the answer is parsed. A conforming answer is one
 *       small JSON object; two thousand characters is generous for that and small enough that a
 *       model which decided to write an essay, or an endpoint that echoed something back, is
 *       refused rather than parsed.</li>
 * </ul>
 *
 * <p>A record rather than a class, and validated in its canonical constructor, because
 * {@code ArchitectureTest} requires it: a bad value fails the context refresh instead of
 * surfacing on the first import nobody is watching.
 *
 * @param minConfidence       the score at or above which an answer is believed and learned back
 * @param maxCallsPerBatch    the default per-invocation cap on AI calls
 * @param maxAnswerCharacters the longest answer that will be parsed at all
 */
@ConfigurationProperties("trove.ai.categorisation")
public record AiCategorisationProperties(BigDecimal minConfidence,
                                         Integer maxCallsPerBatch,
                                         Integer maxAnswerCharacters) {

    /** The old service's threshold, at every tier, kept. */
    public static final BigDecimal DEFAULT_MIN_CONFIDENCE = new BigDecimal("0.70");

    /** Distinct merchants one invocation may spend an AI call on. */
    public static final int DEFAULT_MAX_CALLS_PER_BATCH = 25;

    /** Longest answer that is parsed at all; anything longer is a miss. */
    public static final int DEFAULT_MAX_ANSWER_CHARACTERS = 2_000;

    public AiCategorisationProperties {
        minConfidence = minConfidence == null ? DEFAULT_MIN_CONFIDENCE : minConfidence;
        maxCallsPerBatch = maxCallsPerBatch == null
            ? DEFAULT_MAX_CALLS_PER_BATCH : maxCallsPerBatch;
        maxAnswerCharacters = maxAnswerCharacters == null
            ? DEFAULT_MAX_ANSWER_CHARACTERS : maxAnswerCharacters;

        if (minConfidence.signum() <= 0 || minConfidence.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                "trove.ai.categorisation.min-confidence must be a fraction in (0, 1]; a threshold "
                    + "of zero would learn every guess back as a household rule. Got "
                    + minConfidence);
        }
        if (maxCallsPerBatch < 0) {
            throw new IllegalArgumentException(
                "trove.ai.categorisation.max-calls-per-batch cannot be negative; zero switches the "
                    + "tier off for batches. Got " + maxCallsPerBatch);
        }
        if (maxAnswerCharacters < 1) {
            throw new IllegalArgumentException(
                "trove.ai.categorisation.max-answer-characters must be at least 1, got "
                    + maxAnswerCharacters);
        }
    }
}
