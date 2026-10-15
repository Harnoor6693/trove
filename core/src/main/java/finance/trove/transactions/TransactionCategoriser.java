package finance.trove.transactions;

import finance.trove.categorisation.CategorisationResult;
import finance.trove.categorisation.CategorisationService;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The write half of the categorisation seam (§6 D4): it asks
 * {@link CategorisationService} what a row belongs to and answers with the id to store on
 * {@code transaction.category_id}.
 *
 * <p><strong>Why the column and not only the override table.</strong> Nothing wrote
 * {@code transaction.category_id} before this step — the pre-collapse service resolved a category
 * and dropped the answer. Every aggregate downstream (a budget envelope, the tax export, the
 * dashboard) reads the column, so a categorisation that lands only in {@code category_override}
 * moves no budget and appears on no report. §6 D4 calls that out explicitly as the defect to
 * avoid, and this class is where the fix is applied for both entry points at once: an ingested row
 * and a hand-entered one are categorised by exactly the same call.
 *
 * <p><strong>A pin wins.</strong> If a member has pinned this transaction through
 * {@code PATCH /v1/transactions/{id}}, a re-sync must not silently reclassify it — that is the
 * whole purpose of {@link CategoryOverride}, and checking it here is what makes the pin
 * permanent rather than merely first.
 *
 * <p>{@link CategorisationService} is <strong>injected</strong>, never called on {@code this} and
 * never re-implemented: it is a distinct bean with its own {@code readOnly} transaction, which is
 * the D4 self-invocation fix applied by construction. Writing a learned merchant mapping back
 * ({@code MerchantMappingWriter}) and the {@code CategorisationListener} that will consume
 * {@code TransactionsIngestedEvent} are collapse step 16's, deliberately not this step's.
 */
@Component
public class TransactionCategoriser {

    private final CategorisationService categorisation;
    private final CategoryOverrideRepository overrides;

    public TransactionCategoriser(CategorisationService categorisation,
                                  CategoryOverrideRepository overrides) {
        this.categorisation = categorisation;
        this.overrides = overrides;
    }

    /**
     * The category a newly inserted row should carry.
     *
     * @param merchant the merchant string, falling back to the description when a source offered
     *                 no merchant — matching on an empty string would always land on the
     *                 {@code Uncategorised} fallback and make the whole pipeline a no-op for
     *                 every source that only reports a description
     * @return never {@code null}: the pipeline's last tier is the {@code Uncategorised} system
     *         category, resolved by slug rather than by name
     */
    public UUID categoriseOnInsert(UUID householdId, String merchant, String description,
                                   BigDecimal amount, String currency) {
        return categoriseOnInsert(householdId, null, merchant, description, amount, currency);
    }

    /**
     * The same answer, for a caller that knows whose row this is — collapse step 16's half of
     * step 17's follow-up.
     *
     * <p>The pre-existing five-argument {@link CategorisationService#categorise} passes no owner
     * and therefore, by construction, never consults the AI tier: {@code ai_settings} is keyed by
     * user id (V16, owner decision 9) and there is no "the install's AI" to fall back on. Step 17
     * made that structural on purpose and left the note that whoever threads the principal through
     * should call the six-argument overload. This is that overload, and the five-argument one
     * above now delegates to it with a {@code null} owner so that no caller changed behaviour by
     * accident.
     *
     * <p><strong>Who passes what, and why the ingest path deliberately passes nothing.</strong>
     * {@code TransactionService.createManual} passes the principal: a member hand-entering a row
     * is present, has consented, and their own AI settings are the ones that apply.
     * {@code IngestionBatchWriter} keeps calling the five-argument form, and that is not an
     * oversight — it calls this from inside the one short transaction that persists the batch, and
     * an AI round-trip there would pin a pooled database connection for the length of a model
     * call. The owner-aware pass for ingested rows happens afterwards, outside every transaction,
     * in {@link CategorisationListener}.
     *
     * @param ownerUserId the transaction OWNER, or {@code null} for an ownerless row — which runs
     *                    the free offline tiers alone and falls through to {@code Uncategorised},
     *                    because there is no member whose endpoint and whose money the call would
     *                    be
     */
    public UUID categoriseOnInsert(UUID householdId, UUID ownerUserId, String merchant,
                                   String description, BigDecimal amount, String currency) {
        String subject = merchant == null || merchant.isBlank() ? description : merchant;
        CategorisationResult result = categorisation.categorise(householdId, ownerUserId, subject,
            description, amount, currency);
        return result.categoryId();
    }

    /**
     * Whether a member has pinned this transaction's category, in which case ingestion leaves
     * {@code category_id} exactly as it is.
     */
    public boolean isPinned(UUID householdId, UUID transactionId) {
        return overrides.existsByHouseholdIdAndTransactionId(householdId, transactionId);
    }
}
