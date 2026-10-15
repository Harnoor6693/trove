package finance.trove.categorisation;

/**
 * How a {@link MerchantCategoryMap} row came to exist.
 *
 * <p>{@code RULE} — a household member created it explicitly through the rules UI
 * ({@code CategoryRuleController}). {@code USER} — self-learned from a member correcting a
 * transaction's category (step 6's write path, via {@link MerchantMappingWriter}).
 * {@code AI} — a confident AI categorisation result (step 17). Nothing in this step writes a
 * {@code USER} or {@code AI} row through a listener: the seam exists so steps 6, 16 and 17 have
 * somewhere to call, but "no listener yet" (docs/recovery/06-COLLAPSE-PLAN.md §6) is deliberate —
 * {@link MerchantMappingWriter} is exercised directly by its own integration test.
 *
 * <p>Matches the database {@code CHECK (source IN ('RULE','USER','AI'))} on
 * {@code merchant_category_map} (V15) exactly; do not add a value here without a migration to
 * widen the constraint.
 */
public enum MappingSource {
    RULE,
    USER,
    AI
}
