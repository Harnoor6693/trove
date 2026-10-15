package finance.trove.subscriptions.detection;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.categorisation.MerchantNormaliser;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.transactions.Transaction;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Detection groups by {@code finance.trove.categorisation.MerchantNormaliser}, deliberately, and
 * NOT by the source module's own {@code MerchantNameNormalizer}.
 *
 * <p>Two normalisers in one JVM means the same merchant is one subscription on one screen and two
 * on another, and the categorisation package's is the one categories, merchant maps and the
 * community registry already key on. These tests assert the grouping and the normaliser agree on
 * the cases that actually differ between bank strings — a store number, a card/reference suffix,
 * an apostrophe, an accent.
 */
class MerchantGroupingTest {

    @ParameterizedTest(name = "\"{0}\" and \"{1}\" are one subscription")
    @CsvSource({
        "SPOTIFY,             Spotify",
        "'TIM HORTONS #42',   'Tim Horton''s'",
        "'SQ *COFFEE SHOP 8842', 'SQ *Coffee Shop'",
        "'AMAZON.COM*1A2B3C', 'amazon.com'",
        "CAFE,                café",
        "'NETFLIX  #1234',    NETFLIX",
    })
    @DisplayName("Two bank strings for one merchant collapse into one group")
    void oneMerchantIsOneGroup(String rawOne, String rawTwo) {
        assertThat(MerchantNormaliser.normalise(rawOne))
            .as("the two raw strings must normalise to the same key before grouping can help")
            .isEqualTo(MerchantNormaliser.normalise(rawTwo));

        Map<String, List<Transaction>> grouped = SubscriptionDetectionService.groupByMerchant(
            List.of(charge(rawOne, LocalDate.of(2026, 7, 4)),
                charge(rawTwo, LocalDate.of(2026, 8, 4)),
                charge(rawOne, LocalDate.of(2026, 9, 4))));

        assertThat(grouped)
            .as("SPOTIFY #1234 and Spotify are one subscription, not two")
            .hasSize(1);
        assertThat(grouped.values().iterator().next()).hasSize(3);
        assertThat(grouped.keySet()).containsExactly(MerchantNormaliser.normalise(rawOne));
    }

    @Test
    @DisplayName("Genuinely different merchants stay apart")
    void differentMerchantsAreDifferentGroups() {
        Map<String, List<Transaction>> grouped = SubscriptionDetectionService.groupByMerchant(
            List.of(charge("SPOTIFY", LocalDate.of(2026, 7, 4)),
                charge("NETFLIX", LocalDate.of(2026, 7, 5)),
                charge("UBER EATS", LocalDate.of(2026, 7, 6))));

        assertThat(grouped.keySet())
            .as("the card-suffix pass requires a digit, so UBER EATS does not become UBER")
            .containsExactlyInAnyOrder("SPOTIFY", "NETFLIX", "UBER EATS");
    }

    @Test
    @DisplayName("A merchant name that normalises to nothing is skipped, never grouped as one")
    void anEmptyMerchantIsSkipped() {
        Map<String, List<Transaction>> grouped = SubscriptionDetectionService.groupByMerchant(
            List.of(charge(null, LocalDate.of(2026, 7, 4)),
                charge("   ", LocalDate.of(2026, 7, 5)),
                charge("!!!", LocalDate.of(2026, 7, 6)),
                charge("SPOTIFY", LocalDate.of(2026, 7, 7))));

        assertThat(grouped.keySet())
            .as("grouping anonymous debits under \"\" would collect every one of them into a "
                + "single bogus subscription")
            .containsExactly("SPOTIFY");
    }

    @Test
    @DisplayName("Groups keep first-seen order, so a scan is deterministic")
    void groupsKeepFirstSeenOrder() {
        Map<String, List<Transaction>> grouped = SubscriptionDetectionService.groupByMerchant(
            List.of(charge("ZEBRA", LocalDate.of(2026, 7, 4)),
                charge("APPLE", LocalDate.of(2026, 7, 5)),
                charge("ZEBRA", LocalDate.of(2026, 7, 6))));

        assertThat(grouped.keySet()).containsExactly("ZEBRA", "APPLE");
    }

    private static Transaction charge(String merchant, LocalDate date) {
        return SubscriptionFixtures.charge(merchant, "9.99", date, CurrencyCode.CAD);
    }
}
