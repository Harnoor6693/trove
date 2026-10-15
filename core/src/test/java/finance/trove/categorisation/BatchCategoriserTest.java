package finance.trove.categorisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.ai.AiGateway;
import finance.trove.ai.AiHandle;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cost policy, by behaviour — collapse step 17 scope item 7.
 *
 * <p>One AI call per uncategorised transaction is a five-hundred-call bill for one year of
 * imported CSV, so the bound is the feature. Each case here is one of the three things that bound
 * it: rules run first and for free, two occurrences of one merchant cost one call, and the cap is
 * a hard stop that <em>reports</em> what it left rather than truncating in silence.
 *
 * <p>{@link CategorisationService} is mocked, which is the right seam: what this class is
 * responsible for is how many times it calls the owner-aware overload and with what, not what that
 * overload then decides — {@link DefaultAiCategorisationTierTest} owns that, and
 * {@code CategorisationRuleIT} owns the SQL underneath it.
 */
class BatchCategoriserTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID UNCATEGORISED = UUID.randomUUID();
    private static final UUID COFFEE = UUID.randomUUID();

    private CategorisationService categorisation;
    private CategoryRepository categories;
    private AiGateway gateway;
    private AiHandle handle;
    private BatchCategoriser batch;

    @BeforeEach
    void setUp() {
        categorisation = mock(CategorisationService.class);
        categories = mock(CategoryRepository.class);
        gateway = mock(AiGateway.class);
        handle = mock(AiHandle.class);
        batch = new BatchCategoriser(categorisation, categories, gateway,
            new AiCategorisationProperties(null, null, null));

        // The owner has AI on unless a case says otherwise; the gateway itself is step 12's and
        // never returns null.
        when(gateway.forUser(OWNER)).thenReturn(handle);
        when(handle.isEnabled()).thenReturn(true);

        when(categories.findByHouseholdIdAndSlug(isNull(), eq("system.uncategorised")))
            .thenReturn(Optional.of(CategoryFixture.system(UNCATEGORISED, "system.uncategorised",
                "Uncategorised")));
        // Both overloads miss on everything unless a case says otherwise: the five-argument one
        // is the free tiers, the six-argument one is those plus the AI tier.
        when(categorisation.categorise(any(), anyString(), any(), any(), any()))
            .thenReturn(uncategorised());
        when(categorisation.categorise(any(), any(UUID.class), anyString(), any(), any(), any()))
            .thenReturn(uncategorised());
    }

    @Test
    @DisplayName("rules run first, for every row, and a resolved row never reaches the AI overload")
    void rulesRunFirstAndCostNothing() {
        when(categorisation.categorise(eq(HOUSEHOLD), eq("STARBUCKS"), any(), any(), any()))
            .thenReturn(new CategorisationResult(COFFEE, "Coffee Shops", new BigDecimal("0.950"),
                MappingSource.RULE));

        BatchCategoriser.Outcome outcome = batch.categorise(HOUSEHOLD, OWNER,
            List.of(item("STARBUCKS"), item("STARBUCKS")), 10);

        assertThat(outcome.aiCallsAttempted()).isZero();
        assertThat(outcome.leftUncategorisedAtCap()).isZero();
        assertThat(outcome.results()).hasSize(2)
            .allSatisfy((id, result) -> assertThat(result.categoryId()).isEqualTo(COFFEE));
        verify(categorisation, never()).categorise(any(), any(UUID.class), anyString(), any(),
            any(), any());
    }

    @Test
    @DisplayName("two identical merchants in one batch cost exactly one call")
    void duplicateMerchantsAreOneCall() {
        List<BatchCategoriser.Item> items = List.of(
            item("Tim Horton's #402"), item("TIM HORTONS"), item("Tim Hortons #91"));
        answersWithAi("Tim Horton's #402");

        BatchCategoriser.Outcome outcome = batch.categorise(HOUSEHOLD, OWNER, items, 10);

        assertThat(outcome.aiCallsAttempted())
            .as("three rows, one normalised merchant, one call")
            .isEqualTo(1);
        assertThat(outcome.results().values())
            .allSatisfy(result -> assertThat(result.categoryId()).isEqualTo(COFFEE));
    }

    @Test
    @DisplayName("the cap really stops at N calls and reports the rows it left")
    void theCapIsAHardStopThatReports() {
        List<BatchCategoriser.Item> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(item("MERCHANT " + i));
        }
        // Two rows share the last merchant, so the cap leaves three rows, not two.
        items.add(item("MERCHANT 9"));

        BatchCategoriser.Outcome outcome = batch.categorise(HOUSEHOLD, OWNER, items, 8);

        assertThat(outcome.aiCallsAttempted()).isEqualTo(8);
        assertThat(outcome.leftUncategorisedAtCap())
            .as("merchants 8 and 9 were never attempted; merchant 9 covers two rows")
            .isEqualTo(3);
        verify(categorisation, times(8)).categorise(eq(HOUSEHOLD), eq(OWNER), anyString(), any(),
            any(), any());
        assertThat(outcome.results()).hasSize(11);
    }

    @Test
    @DisplayName("a cap of zero switches the tier off for this batch without touching the install")
    void aCapOfZeroMakesNoCalls() {
        BatchCategoriser.Outcome outcome =
            batch.categorise(HOUSEHOLD, OWNER, List.of(item("ANYTHING")), 0);

        assertThat(outcome.aiCallsAttempted()).isZero();
        assertThat(outcome.leftUncategorisedAtCap()).isEqualTo(1);
        verify(categorisation, never()).categorise(any(), any(UUID.class), anyString(), any(),
            any(), any());
    }

    @Test
    @DisplayName("no owner means no AI at all — an ownerless batch runs the free tiers only")
    void anOwnerlessBatchNeverCallsTheModel() {
        BatchCategoriser.Outcome outcome =
            batch.categorise(HOUSEHOLD, null, List.of(item("ANYTHING")), 10);

        assertThat(outcome.aiCallsAttempted()).isZero();
        assertThat(outcome.leftUncategorisedAtCap())
            .as("nothing was truncated by the cap; there was simply nobody to ask")
            .isZero();
        verify(categorisation, never()).categorise(any(), any(UUID.class), anyString(), any(),
            any(), any());
    }

    @Test
    @DisplayName("an owner whose AI is off costs one settings read and reports honest zeroes")
    void anAiLessOwnerNeverReachesTheTier() {
        when(handle.isEnabled()).thenReturn(false);
        List<BatchCategoriser.Item> items = new ArrayList<>();
        for (int i = 0; i < AiCategorisationProperties.DEFAULT_MAX_CALLS_PER_BATCH + 5; i++) {
            items.add(item("MERCHANT " + i));
        }

        BatchCategoriser.Outcome outcome = batch.categorise(HOUSEHOLD, OWNER, items);

        assertThat(outcome.results()).hasSize(items.size());
        assertThat(outcome.aiCallsAttempted())
            .as("no call was attempted, because there is no AI to attempt one with — this is the "
                + "default install, and the count must not read 25")
            .isZero();
        assertThat(outcome.leftUncategorisedAtCap())
            .as("nothing was truncated by a cap; there was simply nothing to truncate")
            .isZero();
        verify(categorisation, never()).categorise(any(), any(UUID.class), anyString(), any(),
            any(), any());
        verify(gateway, times(1)).forUser(OWNER);
    }

    @Test
    @DisplayName("an ownerless batch does not even read whose AI it would have been")
    void anOwnerlessBatchNeverAsksTheGateway() {
        batch.categorise(HOUSEHOLD, null, List.of(item("ANYTHING")), 10);

        verifyNoInteractions(gateway);
    }

    @Test
    @DisplayName("an AI miss leaves the row Uncategorised and is not counted against the cap")
    void anAiMissIsNotACapCasualty() {
        BatchCategoriser.Outcome outcome =
            batch.categorise(HOUSEHOLD, OWNER, List.of(item("MYSTERY")), 10);

        assertThat(outcome.aiCallsAttempted()).isEqualTo(1);
        assertThat(outcome.leftUncategorisedAtCap())
            .as("the row was asked about and missed; it was not truncated")
            .isZero();
        assertThat(outcome.results().values())
            .allSatisfy(result -> assertThat(result.categoryId()).isEqualTo(UNCATEGORISED));
    }

    @Test
    @DisplayName("a row with no merchant falls back to its description, as ingestion does")
    void aBlankMerchantUsesTheDescription() {
        answersWithAi("INTERAC PURCHASE");

        BatchCategoriser.Outcome outcome = batch.categorise(HOUSEHOLD, OWNER,
            List.of(new BatchCategoriser.Item(UUID.randomUUID(), "  ", "INTERAC PURCHASE",
                new BigDecimal("4.50"), "CAD")), 10);

        assertThat(outcome.aiCallsAttempted()).isEqualTo(1);
        assertThat(outcome.results().values())
            .allSatisfy(result -> assertThat(result.categoryId()).isEqualTo(COFFEE));
    }

    @Test
    @DisplayName("an empty batch is an empty outcome and touches nothing")
    void anEmptyBatchDoesNothing() {
        BatchCategoriser.Outcome outcome = batch.categorise(HOUSEHOLD, OWNER, List.of(), 10);

        assertThat(outcome.results()).isEmpty();
        assertThat(outcome.aiCallsAttempted()).isZero();
        verify(categories, never()).findByHouseholdIdAndSlug(any(), anyString());
    }

    @Test
    @DisplayName("the default cap is the configured one, not an unbounded run")
    void theDefaultCapIsApplied() {
        List<BatchCategoriser.Item> items = new ArrayList<>();
        for (int i = 0; i < AiCategorisationProperties.DEFAULT_MAX_CALLS_PER_BATCH + 5; i++) {
            items.add(item("MERCHANT " + i));
        }

        BatchCategoriser.Outcome outcome = batch.categorise(HOUSEHOLD, OWNER, items);

        assertThat(outcome.aiCallsAttempted())
            .isEqualTo(AiCategorisationProperties.DEFAULT_MAX_CALLS_PER_BATCH);
        assertThat(outcome.leftUncategorisedAtCap()).isEqualTo(5);
    }

    @Test
    @DisplayName("a missing Uncategorised seed row is a configuration error, not a silent no-op")
    void aMissingFallbackRowIsFatal() {
        when(categories.findByHouseholdIdAndSlug(isNull(), eq("system.uncategorised")))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> batch.categorise(HOUSEHOLD, OWNER, List.of(item("X")), 1))
            .isInstanceOf(IllegalStateException.class);
    }

    private void answersWithAi(String merchant) {
        when(categorisation.categorise(eq(HOUSEHOLD), eq(OWNER), eq(merchant), any(), any(),
            any()))
            .thenReturn(new CategorisationResult(COFFEE, "Coffee Shops", new BigDecimal("0.930"),
                MappingSource.AI));
    }

    private static BatchCategoriser.Item item(String merchant) {
        return new BatchCategoriser.Item(UUID.randomUUID(), merchant, "a description",
            new BigDecimal("4.50"), "CAD");
    }

    private static CategorisationResult uncategorised() {
        return new CategorisationResult(UNCATEGORISED, "Uncategorised", new BigDecimal("0.000"),
            null);
    }
}
