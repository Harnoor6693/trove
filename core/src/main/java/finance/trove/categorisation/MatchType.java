package finance.trove.categorisation;

/**
 * How a {@link MerchantCategoryMap} row's {@code normalisedMerchant} is compared against an
 * incoming, normalised merchant string.
 *
 * <p>{@code EXACT} requires equality; {@code PREFIX} requires the stored value to be a leading
 * substring of the supplied one — the direction the old service's D4 finding got backwards (its
 * derived query required the stored value to start with the SUPPLIED string, which for a
 * genuine short prefix like {@code "APPLE"} against a lookup of {@code "APPLE STORE"} never
 * matches; see {@link MerchantCategoryMapRepository}). Matches the database
 * {@code CHECK (match_type IN ('EXACT','PREFIX'))} on {@code merchant_category_map} (V15).
 */
public enum MatchType {
    EXACT,
    PREFIX
}
