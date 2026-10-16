package finance.trove.receipts.taxsplit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Reverse-calculates GST, PST and HST out of a tax-inclusive receipt total
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6, open question 1).
 *
 * <p>The source's arithmetic is kept — the tax inside a total {@code T} at a combined rate {@code r}
 * is {@code T - T/(1+r)} — with two changes. It works in {@link BigDecimal} rates rather than
 * {@code double} ones, which {@code ArchitectureTest} bans on domain types (§9 risk 6) and which
 * matters here because 9.975 % is not representable in binary; and it computes the split at the
 * scale the column carries, {@code NUMERIC(19,4)}, rather than the source's two places.
 *
 * <p>The GST/PST division apportions the total tax between the two rates and then <em>subtracts</em>
 * to get the second component, so the two always add back to the total tax exactly — no rounding
 * residue appears or disappears.
 *
 * <p>This is a display convenience on the receipt and nothing more. It is never part of the tax
 * export (§2.9); the full data export carries it as stored metadata.
 */
@Service
public class TaxSplitService {

    /** {@code receipt.gst_amount} and friends are {@code NUMERIC(19,4)}. */
    private static final int MONEY_SCALE = 4;

    /** Intermediate scale, wide enough that QST's five-figure rate does not lose a cent. */
    private static final int WORKING_SCALE = 10;

    /**
     * Splits a tax-inclusive total.
     *
     * @param total  the amount printed on the receipt, tax included; null or non-positive yields
     *               {@link TaxSplit#zero()}
     * @param region an ISO 3166-2 CA province code from the member's {@code users.region}; an
     *               unknown or absent one yields {@link TaxSplit#zero()} rather than a default
     *               province's figures
     */
    public TaxSplit split(BigDecimal total, String region) {
        if (total == null || total.signum() <= 0) {
            return TaxSplit.zero();
        }
        Optional<ProvinceTaxRates.Rates> found = ProvinceTaxRates.of(region);
        if (found.isEmpty()) {
            return TaxSplit.zero();
        }
        ProvinceTaxRates.Rates rates = found.get();
        BigDecimal combined = rates.combined();
        if (combined.signum() <= 0) {
            return TaxSplit.zero();
        }

        BigDecimal preTax = total.divide(BigDecimal.ONE.add(combined), WORKING_SCALE,
            RoundingMode.HALF_UP);
        BigDecimal totalTax = total.subtract(preTax).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        if (rates.hst().signum() > 0) {
            return new TaxSplit(TaxSplit.scaled(BigDecimal.ZERO), TaxSplit.scaled(BigDecimal.ZERO),
                totalTax);
        }
        if (rates.pst().signum() > 0) {
            BigDecimal gst = totalTax
                .multiply(rates.gst().divide(combined, WORKING_SCALE, RoundingMode.HALF_UP))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            // Subtracted rather than computed independently, so the two components always add
            // back to totalTax and a rounded half-cent cannot vanish or be counted twice.
            return new TaxSplit(gst, totalTax.subtract(gst), TaxSplit.scaled(BigDecimal.ZERO));
        }
        return new TaxSplit(totalTax, TaxSplit.scaled(BigDecimal.ZERO),
            TaxSplit.scaled(BigDecimal.ZERO));
    }
}
