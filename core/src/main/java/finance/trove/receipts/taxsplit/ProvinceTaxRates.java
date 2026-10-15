package finance.trove.receipts.taxsplit;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * The Canadian sales-tax rates, as of the 2026 tax year
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.6).
 *
 * <p><strong>A compile-time table rather than configuration, which is a change from the
 * source.</strong> The source bound {@code trove.tax-split.province-rates} from
 * {@code application.yml} and failed the context outright if {@code default-rates} was absent —
 * a rate table that an operator was expected to maintain, could get wrong silently, and whose
 * absence broke boot rather than receipts. Three reasons it is here instead:
 *
 * <ol>
 *   <li>These are statutory rates, not a preference. An install that sets them to something else
 *       is producing a number that is wrong, and nothing about a self-hosted deployment makes GST
 *       5.0 % locally.</li>
 *   <li>Collapse steps 11, 13, 14 and 16 run in parallel worktrees and no step of this wave owns
 *       {@code application.yml}; a rate block added there is a merge conflict at best.</li>
 *   <li>An unknown or absent province now yields {@link Optional#empty()} rather than silently
 *       applying a "default" rate. The source applied its default to any province it did not
 *       recognise, which meant a typo produced a plausible-looking Ontario figure for British
 *       Columbia. No rates means no split, which is honest.</li>
 * </ol>
 *
 * <p>The figures are the ones in force for 2026: 5 % GST federally; HST at 13 % in ON and 15 % in
 * NB, NL, NS and PE; PST beside GST at 7 % in BC and MB, 6 % in SK, and QST at 9.975 % in QC; GST
 * alone in AB, NT, NU and YT.
 */
public final class ProvinceTaxRates {

    /** One jurisdiction's rates, as fractions. */
    public record Rates(BigDecimal gst, BigDecimal pst, BigDecimal hst) {

        static Rates gstOnly(String gst) {
            return new Rates(new BigDecimal(gst), BigDecimal.ZERO, BigDecimal.ZERO);
        }

        static Rates gstPlusPst(String gst, String pst) {
            return new Rates(new BigDecimal(gst), new BigDecimal(pst), BigDecimal.ZERO);
        }

        static Rates hstOnly(String hst) {
            return new Rates(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(hst));
        }

        /** The single rate that applies to the whole total, whichever regime is in force. */
        public BigDecimal combined() {
            return hst.signum() > 0 ? hst : gst.add(pst);
        }
    }

    private static final String GST = "0.05";

    private static final Map<String, Rates> BY_PROVINCE = Map.ofEntries(
        Map.entry("AB", Rates.gstOnly(GST)),
        Map.entry("BC", Rates.gstPlusPst(GST, "0.07")),
        Map.entry("MB", Rates.gstPlusPst(GST, "0.07")),
        Map.entry("NB", Rates.hstOnly("0.15")),
        Map.entry("NL", Rates.hstOnly("0.15")),
        Map.entry("NS", Rates.hstOnly("0.15")),
        Map.entry("NT", Rates.gstOnly(GST)),
        Map.entry("NU", Rates.gstOnly(GST)),
        Map.entry("ON", Rates.hstOnly("0.13")),
        Map.entry("PE", Rates.hstOnly("0.15")),
        Map.entry("QC", Rates.gstPlusPst(GST, "0.09975")),
        Map.entry("SK", Rates.gstPlusPst(GST, "0.06")),
        Map.entry("YT", Rates.gstOnly(GST)));

    private ProvinceTaxRates() {
    }

    /**
     * The rates for an ISO 3166-2 CA province code.
     *
     * @return empty for null, blank, or a code that is not one of the thirteen — which yields no
     *         split at all rather than a default province's figures
     */
    public static Optional<Rates> of(String region) {
        if (region == null || region.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
            BY_PROVINCE.get(region.strip().toUpperCase(java.util.Locale.ROOT)));
    }
}
