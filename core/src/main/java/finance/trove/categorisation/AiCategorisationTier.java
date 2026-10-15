package finance.trove.categorisation;

import java.util.Optional;
import java.util.UUID;

/**
 * The AI tier of {@link CategorisationService#categorise} — collapse step 17's whole surface
 * inside the pipeline ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 D4, §2.3's
 * {@code AiCategorisationService} REWRITE row).
 *
 * <p><strong>Why a separate bean behind a one-method interface.</strong> {@code categorise} is
 * {@code @Transactional(readOnly = true)} and must never return {@code null}, and both of those
 * are properties this tier could destroy if it lived inside that class. It writes — a confident
 * answer is learned back through {@link MerchantMappingWriter}, which is
 * {@code REQUIRES_NEW} and, per its own javadoc, only opens that second transaction when it is
 * reached through the injected bean rather than through {@code this}. A tier that were a private
 * method on {@link CategorisationService} would reach the writer through the same object graph
 * the D4 self-invocation bug lived in. So: a distinct Spring bean, injected, called through its
 * proxy, with {@link Optional#empty()} as the only way it can fail.
 *
 * <p><strong>Empty means "carry on".</strong> Every kind of failure lands here as an empty
 * Optional and never as an exception: AI off for this member, a privacy tier that forbids the
 * payload, an unreachable or slow endpoint, an answer that does not parse, an answer below the
 * confidence threshold, an answer naming a category this household cannot see. The caller falls
 * through to the {@code Uncategorised} system category exactly as it does on an install with no
 * AI at all. An import of five hundred transactions must not fail because one member's endpoint
 * is off.
 *
 * <p><strong>An install with no AI runs the four tiers it ran before, unchanged.</strong>
 * {@link CategorisationService}'s original five-argument {@code categorise} passes no user id and
 * therefore never consults this tier at all — not even to ask whether AI is on. Only the
 * owner-aware six-argument overload reaches it, and only after tiers 1–3 have missed.
 */
public interface AiCategorisationTier {

    /**
     * Asks the transaction owner's own AI what this merchant is, and learns a confident answer
     * back into the household's {@code merchant_category_map}.
     *
     * @param householdId whose ledger this merchant belongs to. The candidate categories, the
     *                    slug resolution and the learned mapping are all scoped to it
     * @param userId      the <strong>transaction owner</strong>, never the caller and never the
     *                    install: {@code ai_settings} is keyed by user id (V16, owner decision 9),
     *                    so the endpoint, the key, the model and the privacy tier being used are
     *                    this member's. On the ingest path this is
     *                    {@code TransactionsIngestedEvent.userId}; on a manual entry it is the
     *                    principal. {@code null} — an ownerless row — means no AI, because there
     *                    is no member whose endpoint and whose money this would be
     * @param merchant    the raw, bank-supplied merchant string; normalised internally
     * @return the resolved category, or empty for every kind of miss
     */
    Optional<CategorisationResult> categorise(UUID householdId, UUID userId, String merchant);
}
