package finance.trove.common.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Canada and the USA are both first-class (owner decision 3); the tax <em>engine</em> is cut
 * (owner decision 2), so nothing here computes a tax number.
 */
class JurisdictionTest {

    @Test
    void everyJurisdictionRoundTripsThroughItsDefaultCurrency() {
        for (Jurisdiction jurisdiction : Jurisdiction.values()) {
            assertThat(jurisdiction.defaultCurrency().defaultJurisdiction()).isEqualTo(jurisdiction);
        }
    }

    @Test
    @DisplayName("onboarding defaults: CA -> CAD/en-CA, US -> USD/en-US")
    void onboardingDefaults() {
        assertThat(Jurisdiction.CA.defaultCurrency()).isEqualTo(CurrencyCode.CAD);
        assertThat(Jurisdiction.CA.defaultLocaleTag()).isEqualTo("en-CA");
        assertThat(Jurisdiction.US.defaultCurrency()).isEqualTo(CurrencyCode.USD);
        assertThat(Jurisdiction.US.defaultLocaleTag()).isEqualTo("en-US");
    }
}
