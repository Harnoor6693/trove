package finance.trove.receipts.taxsplit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CA-only reverse tax calculation (§2.6, open question 1).
 *
 * <p>Every figure below is checked as {@code total - total/(1+r)} at the column's own scale.
 * The unknown-province case is the behavioural change from the source, which applied a configured
 * "default" to anything it did not recognise and so produced a plausible-looking Ontario figure
 * for a typo.
 */
class TaxSplitServiceTest {

    private final TaxSplitService service = new TaxSplitService();

    @Test
    @DisplayName("an HST province puts the whole tax in hst and leaves gst and pst at zero")
    void hstProvince() {
        TaxSplit split = service.split(new BigDecimal("113.00"), "ON");

        assertThat(split.hst()).isEqualByComparingTo(new BigDecimal("13.00"));
        assertThat(split.gst()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(split.pst()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("a GST+PST province splits proportionally and the two add back to the total tax")
    void gstPlusPstProvince() {
        TaxSplit split = service.split(new BigDecimal("112.00"), "BC");

        assertThat(split.hst()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(split.gst()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(split.pst()).isEqualByComparingTo(new BigDecimal("7.00"));
        // The second component is subtracted rather than computed, so no rounded half-cent can
        // appear or disappear between the two.
        assertThat(split.gst().add(split.pst())).isEqualByComparingTo(new BigDecimal("12.00"));
    }

    @Test
    @DisplayName("a GST-only territory puts the whole tax in gst")
    void gstOnlyTerritory() {
        TaxSplit split = service.split(new BigDecimal("105.00"), "AB");

        assertThat(split.gst()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(split.pst()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(split.hst()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Quebec's five-figure rate survives, because the arithmetic is not in a double")
    void quebecsAwkwardRate() {
        TaxSplit split = service.split(new BigDecimal("114.975"), "QC");

        assertThat(split.gst().add(split.pst()))
            .isEqualByComparingTo(new BigDecimal("14.975"));
    }

    @Test
    @DisplayName("an unknown or absent province yields no split, not a default province's figures")
    void anUnknownProvinceYieldsNothing() {
        assertThat(service.split(new BigDecimal("113.00"), "ZZ")).isEqualTo(TaxSplit.zero());
        assertThat(service.split(new BigDecimal("113.00"), null)).isEqualTo(TaxSplit.zero());
        assertThat(service.split(new BigDecimal("113.00"), "  ")).isEqualTo(TaxSplit.zero());
    }

    @Test
    @DisplayName("a null or non-positive total yields no split")
    void aMissingTotalYieldsNothing() {
        assertThat(service.split(null, "ON")).isEqualTo(TaxSplit.zero());
        assertThat(service.split(BigDecimal.ZERO, "ON")).isEqualTo(TaxSplit.zero());
        assertThat(service.split(new BigDecimal("-5.00"), "ON")).isEqualTo(TaxSplit.zero());
    }

    @Test
    @DisplayName("every one of the thirteen provinces and territories has rates")
    void allThirteenJurisdictionsResolve() {
        for (String code : new String[] {"AB", "BC", "MB", "NB", "NL", "NS", "NT", "NU", "ON",
            "PE", "QC", "SK", "YT"}) {
            assertThat(ProvinceTaxRates.of(code))
                .as("%s must have rates", code)
                .isPresent();
        }
        assertThat(ProvinceTaxRates.of("on")).isPresent();
    }
}
