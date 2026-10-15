package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link MerchantNormaliser} — collapse step 5's scope names Unicode,
 * punctuation, card-suffix stripping and the D4 prefix bug explicitly.
 */
class MerchantNormaliserTest {

    @Test
    @DisplayName("null and blank normalise to the empty string")
    void nullAndBlank() {
        assertThat(MerchantNormaliser.normalise(null)).isEmpty();
        assertThat(MerchantNormaliser.normalise("")).isEmpty();
        assertThat(MerchantNormaliser.normalise("   ")).isEmpty();
    }

    @Test
    @DisplayName("Unicode: accented characters fold to their base letter")
    void unicodeFolding() {
        assertThat(MerchantNormaliser.normalise("café")).isEqualTo("CAFE");
        assertThat(MerchantNormaliser.normalise("Déjà Vu Café")).isEqualTo("DEJA VU CAFE");
        assertThat(MerchantNormaliser.normalise("naïve résumé")).isEqualTo("NAIVE RESUME");
    }

    @Test
    @DisplayName("punctuation and store numbers are stripped")
    void punctuationAndStoreNumbers() {
        assertThat(MerchantNormaliser.normalise("Tim Hortons #42")).isEqualTo("TIM HORTONS");
        assertThat(MerchantNormaliser.normalise("Tim Horton's #42!")).isEqualTo("TIM HORTONS");
        assertThat(MerchantNormaliser.normalise("AMAZON.COM"))
            .as("a period is punctuation and becomes a space, then the run collapses")
            .isEqualTo("AMAZON COM");
        assertThat(MerchantNormaliser.normalise("PETRO-CANADA")).isEqualTo("PETRO CANADA");
    }

    @Test
    @DisplayName("a trailing card/reference suffix is stripped")
    void cardSuffixStripping() {
        assertThat(MerchantNormaliser.normalise("SQ *COFFEE SHOP 8842"))
            .isEqualTo("SQ COFFEE SHOP");
        assertThat(MerchantNormaliser.normalise("AMAZON.COM*1A2B3C"))
            .isEqualTo("AMAZON COM");
        assertThat(MerchantNormaliser.normalise("SPOTIFY 4629"))
            .as("a bare trailing digit run of 4+ is a reference/card suffix, not part of the name")
            .isEqualTo("SPOTIFY");
    }

    @Test
    @DisplayName("a short trailing number is not mistaken for a card suffix")
    void shortTrailingNumberSurvives() {
        assertThat(MerchantNormaliser.normalise("7-ELEVEN")).isEqualTo("7 ELEVEN");
        assertThat(MerchantNormaliser.normalise("STORE 24")).isEqualTo("STORE 24");
    }

    @Test
    @DisplayName("a trailing WORD with no digit is never mistaken for a card suffix")
    void trailingWordWithoutDigitsSurvives() {
        // A digit-free four-letter-plus run at the end is an ordinary second word, not a
        // reference code a payment processor appends. An earlier version of the stripping pass
        // matched any trailing alphanumeric run of four or more, regardless of content, which
        // would have turned "UBER EATS" into "UBER" — losing the second half of a perfectly
        // ordinary two-word merchant name.
        assertThat(MerchantNormaliser.normalise("UBER EATS")).isEqualTo("UBER EATS");
        assertThat(MerchantNormaliser.normalise("WHOLE FOODS")).isEqualTo("WHOLE FOODS");
    }

    @Test
    @DisplayName("whitespace collapses and the result is trimmed")
    void whitespaceCollapses() {
        assertThat(MerchantNormaliser.normalise("  Tim   Hortons  ")).isEqualTo("TIM HORTONS");
    }

    @Test
    @DisplayName("the pipeline is idempotent")
    void idempotent() {
        String once = MerchantNormaliser.normalise("Tim Hortons #42!");
        assertThat(MerchantNormaliser.normalise(once)).isEqualTo(once);
    }
}
