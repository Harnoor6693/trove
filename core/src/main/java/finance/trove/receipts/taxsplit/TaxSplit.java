package finance.trove.receipts.taxsplit;

import java.math.BigDecimal;

/**
 * The three Canadian tax components reverse-calculated from a tax-inclusive total
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6).
 *
 * <p>Exactly one group is non-zero for any real jurisdiction: {@code hst} alone in an HST province,
 * {@code gst} and {@code pst} together where both apply, {@code gst} alone elsewhere.
 *
 * <p><strong>This never reaches the tax export.</strong> The tax-time export carries the member's
 * own amounts and labels with no rate and no computed figure of any kind (§2.9, §4.12, open
 * question 1), and step 15's {@code TaxExportIT} greps the generated files for {@code gst} to hold
 * that. The full data export's {@code receipts.json} does carry the stored split.
 */
public record TaxSplit(BigDecimal gst, BigDecimal pst, BigDecimal hst) {

    /** No province, no rates, or a non-Canadian member: three zeros, not three nulls. */
    public static TaxSplit zero() {
        return new TaxSplit(scaled(BigDecimal.ZERO), scaled(BigDecimal.ZERO),
            scaled(BigDecimal.ZERO));
    }

    static BigDecimal scaled(BigDecimal value) {
        return value.setScale(4, java.math.RoundingMode.HALF_UP);
    }
}
