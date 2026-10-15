package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The AI tier's tuning record — collapse step 17.
 *
 * <p>The canonical constructor is where a bad value fails the context refresh instead of surfacing
 * on the first import nobody is watching, which is the reason these are properties on a record and
 * not fields read out of the environment where they are used.
 */
class AiCategorisationPropertiesTest {

    @Test
    @DisplayName("every value defaults, so no application.yml entry is required to run")
    void everythingDefaults() {
        AiCategorisationProperties properties = new AiCategorisationProperties(null, null, null);

        assertThat(properties.minConfidence()).isEqualByComparingTo("0.70");
        assertThat(properties.maxCallsPerBatch()).isEqualTo(25);
        assertThat(properties.maxAnswerCharacters()).isEqualTo(2000);
    }

    @Test
    @DisplayName("the threshold kept is the old service's 0.70, at every tier")
    void theThresholdIsTheOldServiceNumber() {
        assertThat(AiCategorisationProperties.DEFAULT_MIN_CONFIDENCE)
            .isEqualByComparingTo(new BigDecimal("0.70"));
    }

    @Test
    @DisplayName("a threshold of zero is refused — it would learn every guess back as a rule")
    void aZeroThresholdIsRefused() {
        assertThatThrownBy(() -> new AiCategorisationProperties(BigDecimal.ZERO, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trove.ai.categorisation.min-confidence");
    }

    @Test
    @DisplayName("a threshold above 1 is refused — nothing could ever reach it")
    void aThresholdAboveOneIsRefused() {
        assertThatThrownBy(
            () -> new AiCategorisationProperties(new BigDecimal("1.5"), null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative cap is refused; zero is allowed and means 'no AI for batches'")
    void theCapIsBounded() {
        assertThatThrownBy(() -> new AiCategorisationProperties(null, -1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trove.ai.categorisation.max-calls-per-batch");

        assertThat(new AiCategorisationProperties(null, 0, null).maxCallsPerBatch()).isZero();
    }

    @Test
    @DisplayName("an answer cap below one is refused — it would reject every answer")
    void theAnswerCapIsBounded() {
        assertThatThrownBy(() -> new AiCategorisationProperties(null, null, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("trove.ai.categorisation.max-answer-characters");
    }
}
