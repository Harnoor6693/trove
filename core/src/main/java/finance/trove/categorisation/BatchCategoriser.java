package finance.trove.categorisation;

import finance.trove.ai.AiGateway;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Categorises a whole ingested batch with an explicit, bounded AI cost — collapse step 17's
 * entry point for step 16's {@code CategorisationListener}.
 *
 * <p><strong>Why this class exists at all.</strong> One AI call per uncategorised transaction is a
 * five-hundred-call bill for one year of imported CSV. Three things bound it, and all three are
 * here rather than in the tier, because they are properties of a batch and not of a merchant:
 * <ol>
 *   <li><strong>Rules first, for everything.</strong> Every row goes through the free, offline
 *       tiers before any model is asked anything, so a batch whose merchants the household already
 *       has rules for costs nothing at all.</li>
 *   <li><strong>De-duplication by normalised merchant.</strong> Twenty coffees at the same shop are
 *       one merchant and therefore one call. {@link MerchantNormaliser#normalise} is the same
 *       static function every other tier runs its input through, so the grouping cannot disagree
 *       with the lookups.</li>
 *   <li><strong>A hard cap, counted in AI calls.</strong> At most one call is made per distinct
 *       unresolved merchant and at most {@code maxAiCalls} of those are attempted. Rows whose
 *       merchant was never attempted because the cap ran out are <em>counted and reported</em>
 *       ({@link Outcome#leftUncategorisedAtCap()}) rather than silently truncated.</li>
 *   <li><strong>"Is this member's AI even on" is asked once per batch, not once per
 *       merchant.</strong> AI is off by default on every install ({@link AiGateway}'s own javadoc) and the
 *       overwhelming majority of batches will never have an {@code ai_settings} row to read. One
 *       {@link AiGateway#forUser} call decides it for the whole batch; below it the second pass is
 *       skipped entirely, so an AI-less install pays neither the repeated {@code ai_settings}
 *       reads nor a second run of the rule tiers over rows the first pass already showed
 *       unresolved — and reports honest zeroes rather than a count of calls it never made.</li>
 * </ol>
 *
 * <p><strong>The batch gets cheaper every time it runs.</strong> A confident answer is learned
 * back into {@code merchant_category_map} by {@link AiCategorisationTier}, in its own
 * {@code REQUIRES_NEW} transaction, so the same merchant in the next sync is resolved by the EXACT
 * tier and costs nothing. That is finding D4's "every categorisation re-runs the LLM forever",
 * closed. An import with more new merchants than the cap therefore converges over the next few
 * syncs instead of billing all of them at once.
 *
 * <p><strong>There is deliberately no {@code @Scheduled} job here.</strong> Plan §5 item 6
 * enumerates thirteen scheduled jobs and none of them is an AI sweep, {@code ScheduledJobsTest}
 * would need a lock name nobody has assigned, and a nightly job that spends a member's AI budget
 * without them asking is a decision the owner has not made. A backfill over transactions already
 * in the ledger is recorded as an open issue for steps 16 and 18.
 *
 * <p><strong>Transactional expectations, for step 16.</strong> This class is deliberately NOT
 * {@code @Transactional}. Each call to {@link CategorisationService#categorise} opens its own
 * short {@code readOnly} transaction and closes it, and the write-back opens its own
 * {@code REQUIRES_NEW} one. That matters: an AI call can take seconds, and a caller that wrapped
 * the whole batch in one transaction would pin a pooled database connection for the length of
 * every model round-trip. Call this from {@code AFTER_COMMIT}, outside the transaction that writes
 * {@code transaction.category_id}, and then open that write transaction with the answers in hand.
 */
@Service
public class BatchCategoriser {

    private static final Logger LOG = LoggerFactory.getLogger(BatchCategoriser.class);

    /**
     * One row to categorise.
     *
     * @param transactionId the row the answer belongs to; the key of {@link Outcome#results()}
     * @param merchant      the merchant string, or null/blank to fall back to {@code description}
     *                      — the same substitution {@code TransactionCategoriser} makes, because
     *                      several sources report only a description
     * @param description   reserved for a future description-based rule tier; passed through
     * @param amount        reserved for a future value-based rule tier; passed through
     * @param currency      reserved alongside {@code amount}; passed through
     */
    public record Item(UUID transactionId, String merchant, String description, BigDecimal amount,
                       String currency) {
    }

    /**
     * What the batch resolved, and what it cost.
     *
     * @param results                one entry per {@link Item}, keyed by transaction id; every
     *                               value is non-null and carries a non-null category id, because
     *                               the pipeline's last tier is {@code Uncategorised}
     * @param aiCallsAttempted       how many distinct merchants an AI call was actually attempted
     *                               for. Never more than the cap, and zero when there was nothing
     *                               to ask about (the rules resolved everything), nobody to ask
     *                               (no owner), or nobody's AI to ask with (the owner's
     *                               {@link AiGateway} handle is not enabled — the default install).
     *                               An attempt that the model answered badly still counts: it cost
     *                               a call
     * @param leftUncategorisedAtCap how many rows are still {@code Uncategorised} <em>because the
     *                               cap ran out</em> — the number that makes the hard stop visible
     *                               instead of silent. Rows the model was asked about and missed
     *                               are not counted here; they were not truncated, they were
     *                               answered badly. Zero whenever no AI call was possible at all,
     *                               because nothing was then truncated by a cap
     */
    public record Outcome(Map<UUID, CategorisationResult> results, int aiCallsAttempted,
                          int leftUncategorisedAtCap) {

        public Outcome {
            results = Map.copyOf(results);
        }
    }

    private final CategorisationService categorisation;
    private final CategoryRepository categories;
    private final AiGateway gateway;
    private final AiCategorisationProperties properties;

    public BatchCategoriser(CategorisationService categorisation, CategoryRepository categories,
                            AiGateway gateway, AiCategorisationProperties properties) {
        this.categorisation = categorisation;
        this.categories = categories;
        this.gateway = gateway;
        this.properties = properties;
    }

    /**
     * The batch, with the configured default cap
     * ({@code trove.ai.categorisation.max-calls-per-batch}, 25).
     */
    public Outcome categorise(UUID householdId, UUID ownerUserId, List<Item> items) {
        return categorise(householdId, ownerUserId, items, properties.maxCallsPerBatch());
    }

    /**
     * The batch, with an explicit cap.
     *
     * @param householdId whose ledger these rows belong to
     * @param ownerUserId the <strong>transaction owner</strong> whose own AI may be called —
     *                    {@code TransactionsIngestedEvent.userId} on the ingest path. {@code null}
     *                    runs the free tiers only, which is exactly what an ownerless row should
     *                    get
     * @param items       the rows to categorise; an empty list is answered with an empty outcome
     * @param maxAiCalls  the hard ceiling on AI calls this invocation may make. {@code 0} disables
     *                    the tier for this batch without disabling it for the install
     * @return never {@code null}; {@link Outcome#results()} has one entry per item
     */
    public Outcome categorise(UUID householdId, UUID ownerUserId, List<Item> items,
                              int maxAiCalls) {
        if (items == null || items.isEmpty()) {
            return new Outcome(Map.of(), 0, 0);
        }

        Map<UUID, CategorisationResult> results = new LinkedHashMap<>();
        Map<String, List<Item>> unresolvedByMerchant = new LinkedHashMap<>();
        UUID uncategorisedId = uncategorisedCategoryId();

        for (Item item : items) {
            CategorisationResult result = categorisation.categorise(householdId, subject(item),
                item.description(), item.amount(), item.currency());
            results.put(item.transactionId(), result);

            if (ownerUserId == null || !result.categoryId().equals(uncategorisedId)) {
                continue;
            }
            String key = MerchantNormaliser.normalise(subject(item));
            if (!key.isEmpty()) {
                unresolvedByMerchant.computeIfAbsent(key, unused -> new ArrayList<>()).add(item);
            }
        }

        int attempted = 0;
        int leftAtCap = 0;
        if (!unresolvedByMerchant.isEmpty() && aiIsOnFor(ownerUserId)) {
            for (List<Item> sameMerchant : unresolvedByMerchant.values()) {
                if (attempted >= maxAiCalls) {
                    leftAtCap += sameMerchant.size();
                    continue;
                }
                attempted++;
                Item representative = sameMerchant.get(0);
                CategorisationResult answer = categorisation.categorise(householdId, ownerUserId,
                    subject(representative), representative.description(), representative.amount(),
                    representative.currency());
                if (answer.categoryId().equals(uncategorisedId)) {
                    continue;
                }
                for (Item item : sameMerchant) {
                    results.put(item.transactionId(), answer);
                }
            }
        }

        if (attempted > 0 || leftAtCap > 0) {
            LOG.info("ai-categorisation batch: {} rows, {} distinct unresolved merchants, {} AI "
                    + "calls attempted (cap {}), {} rows left uncategorised at the cap",
                items.size(), unresolvedByMerchant.size(), attempted, maxAiCalls, leftAtCap);
        }
        return new Outcome(results, attempted, leftAtCap);
    }

    /**
     * Whether this batch can make an AI call at all — one {@code ai_settings} read for the whole
     * batch, taken before any row is sent through the pipeline a second time.
     *
     * <p>{@link AiGateway#forUser} never returns null and never throws, and
     * {@code AiHandle#isEnabled()} opens no connection: it reads the snapshot the gateway already
     * loaded. Asking it once here rather than letting {@link AiCategorisationTier} discover it per
     * merchant is what keeps the default install — no {@code ai_settings} row anywhere — at one
     * read per batch instead of one per distinct unresolved merchant, and what keeps
     * {@link Outcome#aiCallsAttempted()} an honest count of attempts rather than of intentions.
     */
    private boolean aiIsOnFor(UUID ownerUserId) {
        return ownerUserId != null && gateway.forUser(ownerUserId).isEnabled();
    }

    /** The same merchant-or-description substitution {@code TransactionCategoriser} makes. */
    private static String subject(Item item) {
        return item.merchant() == null || item.merchant().isBlank()
            ? item.description() : item.merchant();
    }

    /**
     * The id of the {@code Uncategorised} system row, read once per batch.
     *
     * <p>Comparing category ids is how "this row is still unresolved" is decided, rather than
     * inspecting {@link CategorisationResult#source()} or its confidence: the community-registry
     * tier also answers with a null source, and a household is free to create a rule whose
     * confidence is zero. The identity of the fallback row is the only thing that means "no tier
     * matched".
     */
    private UUID uncategorisedCategoryId() {
        Optional<Category> fallback = categories.findByHouseholdIdAndSlug(null,
            CategorisationService.UNCATEGORISED_SLUG);
        return fallback
            .orElseThrow(() -> new IllegalStateException(
                "The Uncategorised system category is missing; V14 did not seed correctly"))
            .getId();
    }
}
