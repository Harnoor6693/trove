package finance.trove.subscriptions.detection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.transactions.Transaction;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One merchant's charges turned into one candidate — the cadence, the price, the projection, the
 * signal, and what happens when the history holds two currencies.
 *
 * <p>No database and no clock: "today" is a parameter, so every rule below is a fact about a list
 * of amounts and dates rather than about the machine the test ran on.
 */
class CandidateFinderTest {

    private final SubscriptionDetectionProperties properties =
        new SubscriptionDetectionProperties(null, null, null, null, null, null, null);
    private final CandidateFinder finder = new CandidateFinder(
        new IntervalClassifier(properties), new ConfidenceCalculator(properties), properties);

    @Test
    @DisplayName("Three annual charges are an ANNUAL subscription")
    void threeAnnualChargesAreASubscription() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);
        List<Transaction> charges = SubscriptionFixtures.series("DOMAIN RENEWAL", "24.00", lastSeen,
            BillingCycle.ANNUAL, 3, CurrencyCode.CAD);

        SubscriptionCandidate candidate =
            finder.find("DOMAIN RENEWAL", charges, lastSeen.plusDays(1)).orElseThrow();

        assertThat(candidate.billingCycle()).isEqualTo(BillingCycle.ANNUAL);
        assertThat(candidate.transactionIds()).hasSize(3);
    }

    @Test
    @DisplayName("Three monthly charges are a MONTHLY subscription at that price")
    void threeMonthlyChargesAreASubscription() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);
        List<Transaction> charges = SubscriptionFixtures.monthly("SPOTIFY", "9.99", lastSeen);

        SubscriptionCandidate candidate =
            finder.find("SPOTIFY", charges, lastSeen.plusDays(1)).orElseThrow();

        assertThat(candidate.merchantName()).isEqualTo("SPOTIFY");
        assertThat(candidate.billingCycle()).isEqualTo(BillingCycle.MONTHLY);
        assertThat(candidate.currency()).isEqualTo(CurrencyCode.CAD);
        assertThat(candidate.amount()).isEqualByComparingTo("9.99");
        assertThat(candidate.lastSeenDate()).isEqualTo(lastSeen);
        assertThat(candidate.firstSeenDate()).isEqualTo(lastSeen.minusDays(60));
        assertThat(candidate.nextChargeDate()).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(candidate.overdue()).isFalse();
        assertThat(candidate.baseSignal()).isEqualTo("GREEN");
        assertThat(candidate.transactionIds())
            .as("every charge the conclusion was drawn from is linked")
            .hasSize(3)
            .containsExactlyInAnyOrderElementsOf(charges.stream().map(Transaction::getId).toList());
    }

    @Test
    @DisplayName("Two charges are never a subscription: one interval is a coincidence")
    void twoChargesAreNotASubscription() {
        List<Transaction> charges = SubscriptionFixtures.series("SPOTIFY", "9.99",
            LocalDate.of(2026, 9, 4), BillingCycle.MONTHLY, 2, CurrencyCode.CAD);

        assertThat(finder.find("SPOTIFY", charges, LocalDate.of(2026, 9, 5))).isEmpty();
    }

    @Test
    @DisplayName("The next charge is calendar arithmetic: 31 January projects onto 28 February")
    void aMonthlyChargeOnTheThirtyFirstClampsIntoFebruary() {
        List<Transaction> charges = List.of(
            SubscriptionFixtures.charge("GYM", "45.00", LocalDate.of(2025, 11, 30),
                CurrencyCode.CAD),
            SubscriptionFixtures.charge("GYM", "45.00", LocalDate.of(2025, 12, 31),
                CurrencyCode.CAD),
            SubscriptionFixtures.charge("GYM", "45.00", LocalDate.of(2026, 1, 31),
                CurrencyCode.CAD));

        SubscriptionCandidate candidate =
            finder.find("GYM", charges, LocalDate.of(2026, 2, 1)).orElseThrow();

        assertThat(candidate.billingCycle()).isEqualTo(BillingCycle.MONTHLY);
        assertThat(candidate.nextChargeDate())
            .as("2026 is not a leap year, so the card is charged on the 28th")
            .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    @DisplayName("The same projection in a leap year lands on 29 February")
    void aMonthlyChargeOnTheThirtyFirstClampsOntoALeapDay() {
        List<Transaction> charges = List.of(
            SubscriptionFixtures.charge("GYM", "45.00", LocalDate.of(2027, 11, 30),
                CurrencyCode.CAD),
            SubscriptionFixtures.charge("GYM", "45.00", LocalDate.of(2027, 12, 31),
                CurrencyCode.CAD),
            SubscriptionFixtures.charge("GYM", "45.00", LocalDate.of(2028, 1, 31),
                CurrencyCode.CAD));

        assertThat(finder.find("GYM", charges, LocalDate.of(2028, 2, 1)).orElseThrow()
            .nextChargeDate())
            .isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    @DisplayName("A BIWEEKLY cadence drifts across a month end, and that is correct")
    void aBiweeklyCadenceDriftsAcrossTheMonthEnd() {
        List<Transaction> charges = SubscriptionFixtures.series("BUS PASS", "12.00",
            LocalDate.of(2026, 1, 25), BillingCycle.BIWEEKLY, 3, CurrencyCode.CAD);

        SubscriptionCandidate candidate =
            finder.find("BUS PASS", charges, LocalDate.of(2026, 1, 26)).orElseThrow();

        assertThat(candidate.billingCycle()).isEqualTo(BillingCycle.BIWEEKLY);
        assertThat(candidate.nextChargeDate()).isEqualTo(LocalDate.of(2026, 2, 8));
    }

    @Test
    @DisplayName("A charge later than the cadence tolerates is overdue, and the signal is RED")
    void aMissingChargeIsOverdue() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);
        List<Transaction> charges = SubscriptionFixtures.monthly("SPOTIFY", "9.99", lastSeen);
        LocalDate nextCharge = LocalDate.of(2026, 10, 4);

        // MONTHLY's tolerance is three days, so the projection is not "missing" until it is more
        // than three days old — bank posting delays live inside that window by design.
        assertThat(finder.find("SPOTIFY", charges, nextCharge.plusDays(3)).orElseThrow().overdue())
            .as("three days late is late, not missing")
            .isFalse();

        SubscriptionCandidate missing =
            finder.find("SPOTIFY", charges, nextCharge.plusDays(4)).orElseThrow();
        assertThat(missing.overdue()).isTrue();
        assertThat(missing.baseSignal()).isEqualTo("RED");
        assertThat(missing.nextChargeDate())
            .as("a stale projection is left stale — rolling it forward would claim a charge the "
                + "ledger does not support")
            .isEqualTo(nextCharge);
    }

    @Test
    @DisplayName("A merchant charged at three prices is variable spending, not a subscription")
    void threePriceLevelsAreRejected() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);
        List<Transaction> charges = List.of(
            SubscriptionFixtures.charge("CORNER SHOP", "9.99", lastSeen.minusDays(60),
                CurrencyCode.CAD),
            SubscriptionFixtures.charge("CORNER SHOP", "19.99", lastSeen.minusDays(30),
                CurrencyCode.CAD),
            SubscriptionFixtures.charge("CORNER SHOP", "39.99", lastSeen, CurrencyCode.CAD));

        assertThat(finder.find("CORNER SHOP", charges, lastSeen.plusDays(1))).isEmpty();
    }

    @Test
    @DisplayName("One price change is kept: it is the case the feature exists to report")
    void onePriceChangeIsStillASubscription() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);
        List<Transaction> charges = new ArrayList<>();
        for (int i = 3; i >= 2; i--) {
            charges.add(SubscriptionFixtures.charge("SPOTIFY", "9.99",
                lastSeen.minusDays(30L * i), CurrencyCode.CAD));
        }
        for (int i = 1; i >= 0; i--) {
            charges.add(SubscriptionFixtures.charge("SPOTIFY", "12.99",
                lastSeen.minusDays(30L * i), CurrencyCode.CAD));
        }

        SubscriptionCandidate candidate =
            finder.find("SPOTIFY", charges, lastSeen.plusDays(1)).orElseThrow();

        assertThat(candidate.amount())
            .as("the current price, not a mean of both levels")
            .isEqualByComparingTo("12.99");
    }

    @Test
    @DisplayName("Two currencies for one merchant: the one billed most recently wins")
    void aMerchantWithTwoCurrenciesKeepsTheNewest() {
        List<Transaction> charges = new ArrayList<>();
        charges.addAll(SubscriptionFixtures.series("NEWS", "8.00", LocalDate.of(2026, 4, 4),
            BillingCycle.MONTHLY, 3, CurrencyCode.CAD));
        charges.addAll(SubscriptionFixtures.series("NEWS", "6.00", LocalDate.of(2026, 9, 4),
            BillingCycle.MONTHLY, 3, CurrencyCode.USD));

        SubscriptionCandidate candidate =
            finder.find("NEWS", charges, LocalDate.of(2026, 9, 5)).orElseThrow();

        assertThat(candidate.currency())
            .as("the currency the member is being billed in NOW; Money refuses to add across "
                + "currencies and the row has one currency column, so an averaged amount is not "
                + "an option")
            .isEqualTo(CurrencyCode.USD);
        assertThat(candidate.amount()).isEqualByComparingTo("6.00");
        assertThat(candidate.lastSeenDate()).isEqualTo(LocalDate.of(2026, 9, 4));
    }

    @Test
    @DisplayName("The same merchant with the older currency newest keeps that one instead")
    void theRuleIsAboutRecencyAndNotAboutTheCurrency() {
        List<Transaction> charges = new ArrayList<>();
        charges.addAll(SubscriptionFixtures.series("NEWS", "6.00", LocalDate.of(2026, 4, 4),
            BillingCycle.MONTHLY, 3, CurrencyCode.USD));
        charges.addAll(SubscriptionFixtures.series("NEWS", "8.00", LocalDate.of(2026, 9, 4),
            BillingCycle.MONTHLY, 3, CurrencyCode.CAD));

        assertThat(finder.find("NEWS", charges, LocalDate.of(2026, 9, 5)).orElseThrow().currency())
            .isEqualTo(CurrencyCode.CAD);
    }

    @Test
    @DisplayName("A tie on the last charge date breaks deterministically on the currency code")
    void aTieBreaksDeterministically() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);
        List<Transaction> charges = new ArrayList<>();
        charges.addAll(SubscriptionFixtures.series("NEWS", "8.00", lastSeen,
            BillingCycle.MONTHLY, 3, CurrencyCode.CAD));
        charges.addAll(SubscriptionFixtures.series("NEWS", "6.00", lastSeen,
            BillingCycle.MONTHLY, 3, CurrencyCode.USD));

        Optional<SubscriptionCandidate> first = finder.find("NEWS", charges, lastSeen.plusDays(1));
        List<Transaction> reversed = new ArrayList<>(charges);
        java.util.Collections.reverse(reversed);
        Optional<SubscriptionCandidate> second =
            finder.find("NEWS", reversed, lastSeen.plusDays(1));

        assertThat(first.orElseThrow().currency()).isEqualTo(CurrencyCode.USD);
        assertThat(second.orElseThrow().currency())
            .as("input order must not decide which currency a member sees")
            .isEqualTo(first.orElseThrow().currency());
    }

    @Test
    @DisplayName("The category comes from the most recent charge that carries one")
    void theCategoryComesFromTheMostRecentCharge() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);
        List<Transaction> charges = SubscriptionFixtures.monthly("SPOTIFY", "9.99", lastSeen);
        UUID oldCategory = UUID.randomUUID();
        UUID correctedCategory = UUID.randomUUID();
        charges.get(0).setCategoryId(oldCategory);
        charges.get(2).setCategoryId(correctedCategory);

        assertThat(finder.find("SPOTIFY", charges, lastSeen.plusDays(1)).orElseThrow().categoryId())
            .as("a member who recategorises a subscription is telling the detector what it is "
                + "now; the source took the OLDEST charge's category, so a correction never took")
            .isEqualTo(correctedCategory);
    }

    @Test
    @DisplayName("No category at all is null rather than an invented one")
    void anUncategorisedMerchantHasNoCategory() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);

        assertThat(finder.find("SPOTIFY", SubscriptionFixtures.monthly("SPOTIFY", "9.99", lastSeen),
            lastSeen.plusDays(1)).orElseThrow().categoryId())
            .isNull();
    }

    @Test
    @DisplayName("Charges arriving out of order produce the same candidate")
    void inputOrderDoesNotMatter() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);
        List<Transaction> charges = SubscriptionFixtures.monthly("SPOTIFY", "9.99", lastSeen);
        List<Transaction> shuffled = new ArrayList<>(charges);
        java.util.Collections.reverse(shuffled);

        SubscriptionCandidate inOrder =
            finder.find("SPOTIFY", charges, lastSeen.plusDays(1)).orElseThrow();
        SubscriptionCandidate outOfOrder =
            finder.find("SPOTIFY", shuffled, lastSeen.plusDays(1)).orElseThrow();

        assertThat(outOfOrder.billingCycle()).isEqualTo(inOrder.billingCycle());
        assertThat(outOfOrder.nextChargeDate()).isEqualTo(inOrder.nextChargeDate());
        assertThat(outOfOrder.firstSeenDate()).isEqualTo(inOrder.firstSeenDate());
        assertThat(outOfOrder.lastSeenDate()).isEqualTo(inOrder.lastSeenDate());
        assertThat(outOfOrder.amount()).isEqualByComparingTo(inOrder.amount());
    }

    @Test
    @DisplayName("A candidate's transaction id list is immutable")
    void theCandidateIsAValue() {
        LocalDate lastSeen = LocalDate.of(2026, 9, 4);
        SubscriptionCandidate candidate = finder.find("SPOTIFY",
            SubscriptionFixtures.monthly("SPOTIFY", "9.99", lastSeen),
            lastSeen.plusDays(1)).orElseThrow();

        assertThatThrownBy(() -> candidate.transactionIds().add(UUID.randomUUID()))
            .as("List.copyOf in the compact constructor: a candidate is a value, and a caller "
                + "cannot reach back into one a scan has already acted on")
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
