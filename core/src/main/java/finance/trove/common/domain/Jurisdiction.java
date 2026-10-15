package finance.trove.common.domain;

/**
 * The country whose tax-export line mapping, locale defaults and default currency apply to a
 * user (owner decision 3: Canada and the USA, chosen per user at onboarding).
 *
 * <p>Javadoc updated per {@code docs/recovery/06-COLLAPSE-PLAN.md} §2.11: the tax <em>engine</em>
 * is cut (owner decision 2). Nothing in Trove computes a tax number from this enum. It selects
 * the {@code tax_line_mapping} rows for the tax-time CSV/PDF export (§6), the CA-only receipt
 * GST/PST/HST split, and the default currency and locale offered at onboarding.
 */
public enum Jurisdiction {

    /** Canada — default currency CAD, default locale {@code en-CA}, receipt tax split available. */
    CA,

    /** United States — default currency USD, default locale {@code en-US}, no receipt tax split. */
    US;

    /** The currency offered by default when a user picks this jurisdiction at onboarding. */
    public CurrencyCode defaultCurrency() {
        return switch (this) {
            case CA -> CurrencyCode.CAD;
            case US -> CurrencyCode.USD;
        };
    }

    /** The BCP-47 locale tag offered by default when a user picks this jurisdiction. */
    public String defaultLocaleTag() {
        return switch (this) {
            case CA -> "en-CA";
            case US -> "en-US";
        };
    }
}
