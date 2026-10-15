package finance.trove.subscriptions.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The price-level split that replaced the source's coefficient-of-variation gate.
 *
 * <p>The gate mattered: over a thirteen-month window an ordinary rise from 9.99 to 12.99 has a CV
 * of about 13%, so a CV &le; 0.05 rule threw away exactly the merchants this feature exists to
 * report on. These tests pin the replacement rule — at most two contiguous price levels — and the
 * arithmetic, which is {@link BigDecimal} throughout.
 */
class AmountLevelsTest {

    private static final BigDecimal FIVE_PERCENT = new BigDecimal("5");
    private static final int CENTS = 2;

    @Test
    @DisplayName("A constant price is one level, and the current amount is that price")
    void aConstantPriceIsOneLevel() {
        AmountLevels levels = of(CENTS, "9.99", "9.99", "9.99");

        assertThat(levels.levelCount()).isEqualTo(1);
        assertThat(levels.isSubscriptionLike()).isTrue();
        assertThat(levels.currentAmount()).isEqualByComparingTo("9.99");
        assertThat(levels.priorAmount()).isEmpty();
    }

    @Test
    @DisplayName("One price rise is two levels, newest first — the case the CV gate rejected")
    void onePriceRiseIsTwoLevels() {
        AmountLevels levels = of(CENTS, "9.99", "9.99", "12.99", "12.99");

        assertThat(levels.levelCount()).isEqualTo(2);
        assertThat(levels.isSubscriptionLike()).isTrue();
        assertThat(levels.currentAmount())
            .as("what the member pays NOW is the newest level, not a mean over the history")
            .isEqualByComparingTo("12.99");
        assertThat(levels.priorAmount()).hasValueSatisfying(prior ->
            assertThat(prior).isEqualByComparingTo("9.99"));
    }

    @Test
    @DisplayName("Three or more price levels is variable spending, not a subscription")
    void threeLevelsIsNotASubscription() {
        AmountLevels levels = of(CENTS, "9.99", "12.99", "19.99");

        assertThat(levels.levelCount()).isEqualTo(3);
        assertThat(levels.isSubscriptionLike())
            .as("a merchant charged at three different amounts is a shop, not a subscription")
            .isFalse();
    }

    @Test
    @DisplayName("An empty history is no level at all, and is not subscription-like")
    void anEmptyHistoryIsNotSubscriptionLike() {
        AmountLevels levels = AmountLevels.of(List.of(), FIVE_PERCENT, CENTS);

        assertThat(levels.levelCount()).isZero();
        assertThat(levels.isSubscriptionLike()).isFalse();
        assertThat(levels.priorAmount()).isEmpty();
    }

    @Test
    @DisplayName("Inside the tolerance is one price; outside it is a new one")
    void theToleranceDecidesWhereALevelEnds() {
        // 10.00 -> 10.40 is 4%, inside five. 10.00 -> 10.60 is 6%, outside it.
        assertThat(of(CENTS, "10.00", "10.40", "10.00").levelCount())
            .as("a tax change or a rounding difference is not a new price")
            .isEqualTo(1);
        assertThat(of(CENTS, "10.00", "10.00", "10.60").levelCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("The tolerance is compared without dividing, so a zero amount is well defined")
    void aZeroReferenceOnlyMatchesZero() {
        assertThat(of(CENTS, "0.00", "0.00", "0.00").levelCount()).isEqualTo(1);
        assertThat(of(CENTS, "0.00", "0.00", "0.01").levelCount())
            .as("nothing but exactly zero is within any percentage of zero")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("The current amount is the level's median, so one odd charge does not move it")
    void theCurrentAmountIsTheMedianOfTheNewestLevel() {
        // All four are within 5% of the newest, so they are one level. The median ignores the
        // 10.30 outlier that a mean would drag the answer towards.
        assertThat(of(CENTS, "10.30", "10.00", "10.00", "10.00").currentAmount())
            .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("An even-sized level averages its two middles, rounding HALF_UP at the scale")
    void anEvenLevelAveragesItsMiddlesAndRoundsHalfUp() {
        AmountLevels twoMiddles = of(CENTS, "10.20", "10.00");
        assertThat(twoMiddles.currentAmount()).isEqualByComparingTo("10.10");

        // 10.00 and 10.05 average to 10.025, which HALF_UP at two places is 10.03 — never
        // 10.024999... , which is what the same sum through a double produces.
        AmountLevels halfCent = of(CENTS, "10.05", "10.00");
        assertThat(halfCent.currentAmount()).isEqualByComparingTo("10.03");
    }

    @Test
    @DisplayName("Every reported amount carries exactly the currency's scale")
    void everyAmountCarriesTheCurrencysScale() {
        assertThat(of(CENTS, "9.99", "9.99", "9.99").currentAmount().scale()).isEqualTo(CENTS);
        assertThat(of(4, "9.99", "9.99", "9.99").currentAmount().scale())
            .as("a four-place scale is honoured too, which is what detected_subscription's "
                + "NUMERIC(19,4) column stores")
            .isEqualTo(4);
        assertThat(of(4, "9.99", "9.99", "9.99").currentAmount().toPlainString())
            .isEqualTo("9.9900");
    }

    @Test
    @DisplayName("A tolerance of zero admits only exactly equal charges")
    void aZeroToleranceIsExactEquality() {
        AmountLevels levels = AmountLevels.of(
            List.of(new BigDecimal("9.99"), new BigDecimal("9.99"), new BigDecimal("10.00")),
            BigDecimal.ZERO, CENTS);

        assertThat(levels.levelCount()).isEqualTo(2);
    }

    /** Amounts oldest first, the order the ledger returns them in. */
    private static AmountLevels of(int scale, String... chronological) {
        return AmountLevels.of(java.util.Arrays.stream(chronological).map(BigDecimal::new).toList(),
            FIVE_PERCENT, scale);
    }
}
