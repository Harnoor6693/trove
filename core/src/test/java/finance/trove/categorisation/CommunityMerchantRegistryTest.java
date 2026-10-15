package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "The seed file parses" — collapse step 5's scope. Exercises the real classpath resource
 * (ported from {@code services/categorisation-service/src/main/resources/
 * community-merchant-registry.json}) via the package-private static loader, no Spring context
 * required.
 */
class CommunityMerchantRegistryTest {

    @Test
    @DisplayName("the community-merchant-registry.json resource parses into a non-empty map")
    void theSeedFileParses() {
        Map<String, CommunityMerchantRegistry.Entry> registry =
            CommunityMerchantRegistry.load("/categorisation/community-merchant-registry.json");

        assertThat(registry).isNotEmpty();
        assertThat(registry.get("TIM HORTONS"))
            .isEqualTo(new CommunityMerchantRegistry.Entry("Coffee Shops", new BigDecimal("0.99")));
        assertThat(registry.get("STARBUCKS").categoryName()).isEqualTo("Coffee Shops");
        assertThat(registry.get("UBER").categoryName()).isEqualTo("Ride Share");
    }

    @Test
    @DisplayName("keys are pre-normalised the same way a lookup call normalises its input")
    void keysArePreNormalised() {
        Map<String, CommunityMerchantRegistry.Entry> registry =
            CommunityMerchantRegistry.load("/categorisation/community-merchant-registry.json");

        // The raw JSON entry is "PETRO-CANADA"; MerchantNormaliser turns the hyphen into a space.
        assertThat(registry).containsKey(MerchantNormaliser.normalise("PETRO-CANADA"));
        assertThat(registry).doesNotContainKey("PETRO-CANADA");
    }

    @Test
    @DisplayName("a missing resource degrades to an empty registry rather than failing")
    void missingResourceIsEmptyNotFatal() {
        assertThat(CommunityMerchantRegistry.load("/categorisation/does-not-exist.json")).isEmpty();
    }

    @Test
    @DisplayName("the live component wires the same resource and exposes it via lookup/size")
    void theComponentWiresTheSameResource() {
        CommunityMerchantRegistry registry = new CommunityMerchantRegistry();

        assertThat(registry.size()).isGreaterThan(0);
        assertThat(registry.lookup("STARBUCKS"))
            .isPresent()
            .get()
            .extracting(CommunityMerchantRegistry.Entry::categoryName)
            .isEqualTo("Coffee Shops");
        assertThat(registry.lookup("NOT A REAL MERCHANT")).isEmpty();
    }
}
