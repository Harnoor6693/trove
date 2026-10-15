package finance.trove.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Money;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The six seam events of {@code docs/recovery/06-COLLAPSE-PLAN.md} §6.
 *
 * <p>What is worth asserting about a record is the part that is not generated: every event must
 * carry the household (a listener that scoped by {@code userId} would recompute the wrong
 * ledger's aggregate — owner decision 6), and the two events that carry a pair of amounts must
 * refuse a currency mismatch rather than subtract CAD from USD (§9 risk 5).
 */
class DomainEventsTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @Test
    @DisplayName("every event carries the household and the acting member")
    void everyEventIsHouseholdScoped() {
        List<HouseholdEvent> events = List.of(
            ingested(), categorised(), thresholdCrossed(), syncCompleted(), priceChanged(),
            receiptProcessed());

        assertThat(events).allSatisfy(event -> {
            assertThat(event.householdId()).isEqualTo(HOUSEHOLD);
            assertThat(event.userId()).isEqualTo(USER);
        });
    }

    @Test
    @DisplayName("a null household or member is rejected at construction, not at the listener")
    void nullsAreRejected() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() ->
            new TransactionsIngestedEvent(null, USER, id, List.of(), List.of()))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("householdId");
        assertThatThrownBy(() ->
            new TransactionsIngestedEvent(HOUSEHOLD, null, id, List.of(), List.of()))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("userId");
        assertThatThrownBy(() ->
            new TransactionsIngestedEvent(HOUSEHOLD, USER, null, List.of(), List.of()))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("accountId");
        assertThatThrownBy(() -> new TransactionCategorisedEvent(HOUSEHOLD, USER, id, null, id,
            null, CurrencyCode.CAD)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SyncCompletedEvent(HOUSEHOLD, USER, id, id, null, 0, 0, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("outcome");
        assertThatThrownBy(() -> new ReceiptProcessedEvent(HOUSEHOLD, USER, id, null, null))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("outcome");
        assertThatThrownBy(() -> new BudgetThresholdCrossedEvent(HOUSEHOLD, USER, id, id,
            YearMonth.of(2026, 9), null, Money.of("1.00", CurrencyCode.CAD)))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SubscriptionPriceChangedEvent(HOUSEHOLD, USER, id, null,
            Money.of("1.00", CurrencyCode.CAD), Money.of("2.00", CurrencyCode.CAD)))
            .isInstanceOf(NullPointerException.class).hasMessageContaining("merchantName");
    }

    @Test
    @DisplayName("the ingested id lists are copied, so a mutation after publishing is invisible")
    void ingestedIdListsAreCopied() {
        List<UUID> mutable = new ArrayList<>(List.of(UUID.randomUUID()));
        TransactionsIngestedEvent event = new TransactionsIngestedEvent(
            HOUSEHOLD, USER, UUID.randomUUID(), mutable, List.of());
        mutable.clear();

        assertThat(event.newTransactionIds()).hasSize(1);
        assertThat(event.isEmpty()).isFalse();
        assertThat(new TransactionsIngestedEvent(HOUSEHOLD, USER, UUID.randomUUID(), List.of(),
            List.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a category change carries the previous category, which is gone after the write")
    void categorisedCarriesBothCategories() {
        UUID previous = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        TransactionCategorisedEvent event = new TransactionCategorisedEvent(HOUSEHOLD, USER,
            UUID.randomUUID(), previous, next, YearMonth.of(2026, 9), CurrencyCode.CAD);

        assertThat(event.previousCategoryId()).isEqualTo(previous);
        assertThat(event.categoryId()).isEqualTo(next);
        assertThat(event.month()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(event.currency()).isEqualTo(CurrencyCode.CAD);
    }

    @Test
    @DisplayName("an uncategorised transaction publishes nulls rather than a sentinel category")
    void categorisedAllowsNullCategories() {
        TransactionCategorisedEvent event = new TransactionCategorisedEvent(HOUSEHOLD, USER,
            UUID.randomUUID(), null, null, YearMonth.of(2026, 9), CurrencyCode.USD);

        assertThat(event.previousCategoryId()).isNull();
        assertThat(event.categoryId()).isNull();
    }

    @Test
    void budgetThresholdKnowsWhetherItIsMerelyNearOrActuallyOver() {
        assertThat(thresholdCrossed().isOverspent()).isFalse();
        assertThat(new BudgetThresholdCrossedEvent(HOUSEHOLD, USER, UUID.randomUUID(),
            UUID.randomUUID(), YearMonth.of(2026, 9), Money.of("120.00", CurrencyCode.CAD),
            Money.of("100.00", CurrencyCode.CAD)).isOverspent()).isTrue();
        assertThat(thresholdCrossed().envelopeId()).isNotNull();
        assertThat(thresholdCrossed().categoryId()).isNotNull();
    }

    @Test
    @DisplayName("an envelope cannot be budgeted in one currency and spent in another")
    void budgetThresholdRefusesMixedCurrencies() {
        assertThatThrownBy(() -> new BudgetThresholdCrossedEvent(HOUSEHOLD, USER,
            UUID.randomUUID(), UUID.randomUUID(), YearMonth.of(2026, 9),
            Money.of("90.00", CurrencyCode.USD), Money.of("100.00", CurrencyCode.CAD)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("currency");
    }

    @Test
    void syncOutcomeDistinguishesFailureFromPartialFailure() {
        assertThat(syncCompleted().isSuccess()).isTrue();
        assertThat(syncCompleted().failureReason()).isNull();
        assertThat(syncCompleted().newCount()).isEqualTo(3);
        assertThat(syncCompleted().updatedCount()).isEqualTo(1);
        assertThat(syncCompleted().syncLogId()).isNotNull();

        SyncCompletedEvent failed = new SyncCompletedEvent(HOUSEHOLD, USER, UUID.randomUUID(),
            UUID.randomUUID(), SyncCompletedEvent.Outcome.FAILURE, 0, 0, "credentials expired");
        assertThat(failed.isSuccess()).isFalse();
        assertThat(failed.failureReason()).isEqualTo("credentials expired");
        assertThat(SyncCompletedEvent.Outcome.values())
            .containsExactly(SyncCompletedEvent.Outcome.SUCCESS,
                SyncCompletedEvent.Outcome.PARTIAL_FAILURE, SyncCompletedEvent.Outcome.FAILURE);
    }

    @Test
    void priceChangeReportsASignedDelta() {
        assertThat(priceChanged().delta()).isEqualTo(Money.of("2.00", CurrencyCode.CAD));
        assertThat(priceChanged().merchantName()).isEqualTo("Streaming Co");
        assertThat(priceChanged().subscriptionId()).isNotNull();

        SubscriptionPriceChangedEvent cheaper = new SubscriptionPriceChangedEvent(HOUSEHOLD, USER,
            UUID.randomUUID(), "Streaming Co", Money.of("12.00", CurrencyCode.CAD),
            Money.of("10.00", CurrencyCode.CAD));
        assertThat(cheaper.delta().isNegative()).isTrue();
    }

    @Test
    @DisplayName("a price change cannot also change currency")
    void priceChangeRefusesMixedCurrencies() {
        assertThatThrownBy(() -> new SubscriptionPriceChangedEvent(HOUSEHOLD, USER,
            UUID.randomUUID(), "Streaming Co", Money.of("10.00", CurrencyCode.CAD),
            Money.of("12.00", CurrencyCode.USD)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("currency");
    }

    @Test
    @DisplayName("REVIEW_REQUIRED is the ordinary outcome with no AI configured, not an error")
    void receiptOutcomesCoverTheAiOffPath() {
        assertThat(receiptProcessed().isMatched()).isTrue();
        assertThat(receiptProcessed().outcome()).isEqualTo(ReceiptProcessedEvent.Outcome.COMPLETE);
        assertThat(receiptProcessed().receiptId()).isNotNull();

        ReceiptProcessedEvent manual = new ReceiptProcessedEvent(HOUSEHOLD, USER,
            UUID.randomUUID(), ReceiptProcessedEvent.Outcome.REVIEW_REQUIRED, null);
        assertThat(manual.isMatched()).isFalse();
        assertThat(ReceiptProcessedEvent.Outcome.values())
            .containsExactly(ReceiptProcessedEvent.Outcome.COMPLETE,
                ReceiptProcessedEvent.Outcome.REVIEW_REQUIRED,
                ReceiptProcessedEvent.Outcome.FAILED);
    }

    private static TransactionsIngestedEvent ingested() {
        return new TransactionsIngestedEvent(HOUSEHOLD, USER, UUID.randomUUID(),
            List.of(UUID.randomUUID()), List.of());
    }

    private static TransactionCategorisedEvent categorised() {
        return new TransactionCategorisedEvent(HOUSEHOLD, USER, UUID.randomUUID(), null,
            UUID.randomUUID(), YearMonth.of(2026, 9), CurrencyCode.CAD);
    }

    private static BudgetThresholdCrossedEvent thresholdCrossed() {
        return new BudgetThresholdCrossedEvent(HOUSEHOLD, USER, UUID.randomUUID(),
            UUID.randomUUID(), YearMonth.of(2026, 9), Money.of("90.00", CurrencyCode.CAD),
            Money.of("100.00", CurrencyCode.CAD));
    }

    private static SyncCompletedEvent syncCompleted() {
        return new SyncCompletedEvent(HOUSEHOLD, USER, UUID.randomUUID(), UUID.randomUUID(),
            SyncCompletedEvent.Outcome.SUCCESS, 3, 1, null);
    }

    private static SubscriptionPriceChangedEvent priceChanged() {
        return new SubscriptionPriceChangedEvent(HOUSEHOLD, USER, UUID.randomUUID(),
            "Streaming Co", Money.of("10.00", CurrencyCode.CAD),
            Money.of("12.00", CurrencyCode.CAD));
    }

    private static ReceiptProcessedEvent receiptProcessed() {
        return new ReceiptProcessedEvent(HOUSEHOLD, USER, UUID.randomUUID(),
            ReceiptProcessedEvent.Outcome.COMPLETE, UUID.randomUUID());
    }
}
