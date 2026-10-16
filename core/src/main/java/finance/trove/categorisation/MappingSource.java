package finance.trove.categorisation;

/**
 * How a {@link MerchantCategoryMap} row came to exist.
 *
 * <p>{@code RULE} — a household member created it explicitly through the rules UI
 * ({@code CategoryRuleController}). {@code USER} — reserved for a mapping learned from a
 * member's correction; the CHECK allows it but no code writes one today
 * ({@code PATCH /v1/transactions/{id}} pins a {@code category_override} and does not write a
 * merchant mapping). {@code AI} — a confident AI categorisation, written by
 * {@code DefaultAiCategorisationTier} through {@link MerchantMappingWriter}.
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
