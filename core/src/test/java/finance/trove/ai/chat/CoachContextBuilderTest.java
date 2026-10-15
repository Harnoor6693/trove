package finance.trove.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import finance.trove.budgets.envelope.EnvelopeHealth;
import finance.trove.budgets.envelope.EnvelopeService;
import finance.trove.budgets.envelope.dto.EnvelopeResponse;
import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetPeriodService;
import finance.trove.budgets.period.BudgetType;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.networth.NetWorthSeriesService;
import finance.trove.networth.dto.NetWorthPoint;
import finance.trove.networth.dto.NetWorthSeries;
import finance.trove.networth.dto.NetWorthSeriesResponse;
import finance.trove.subscriptions.dto.SubscriptionCurrencyTotals;
import finance.trove.subscriptions.dto.SubscriptionSummaryResponse;
import finance.trove.subscriptions.subscription.SubscriptionService;
import finance.trove.transactions.dashboard.DashboardService;
import finance.trove.transactions.dashboard.dto.DashboardCurrencyTotals;
import finance.trove.transactions.dashboard.dto.DashboardTodayResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The context block: what it says, what it refuses to say, and what it must never carry
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6; inventory model-manager D1, F1, §12).
 *
 * <p>The source's {@code CoachContextBuilderTest} is deliberately NOT ported. It was WireMock
 * stubs over four peer HTTP calls, and its stubs pinned the wrong contract as correct — it never
 * asserted an outbound header name, which is exactly why D1 (three of the four calls 401'd
 * unconditionally, the fourth was silently zeroed) survived undetected for the life of the service.
 * The four reads are in-process method calls now, so the tests are about the block's content
 * instead: per currency, honest about what it could not read, and carrying the minimum.
 */
class CoachContextBuilderTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-05T18:04:00Z");

    private final DashboardService dashboard = mock(DashboardService.class);
    private final BudgetPeriodService periods = mock(BudgetPeriodService.class);
    private final EnvelopeService envelopes = mock(EnvelopeService.class);
    private final NetWorthSeriesService netWorth = mock(NetWorthSeriesService.class);
    private final SubscriptionService subscriptions = mock(SubscriptionService.class);

    private ExecutorService executor;

    @BeforeEach
    void startExecutor() {
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("a household holding two currencies is reported per currency, never blended")
    void twoCurrenciesAreReportedSeparately() {
        givenDashboard(
            totals(CurrencyCode.CAD, "412.50", "3000.00", "2587.50"),
            totals(CurrencyCode.USD, "88.00", "0.00", "-88.00"));
        givenNoBudget();
        givenNoSubscriptions();
        givenNoSnapshots();

        String block = builder(Duration.ofSeconds(5)).build(audience(CurrencyCode.CAD), NOW);

        assertThat(block)
            .as("one line per currency the household actually holds; no exchange rate exists "
                + "anywhere in this system, so nothing is converted or summed across them")
            .contains("CAD (yours): spent 412.50, income 3000.00, net 2587.50")
            .contains("USD: spent 88.00, income 0.00, net -88.00");
        assertThat(block)
            .as("the member's own currency is marked as theirs, and it is read from their users "
                + "row rather than written as a literal (finding F1)")
            .contains("(yours)");
    }

    @Test
    @DisplayName("the member's own currency decides which line is marked theirs")
    void theOwnCurrencyMarkerFollowsTheMember() {
        givenDashboard(
            totals(CurrencyCode.CAD, "1.00", "2.00", "1.00"),
            totals(CurrencyCode.USD, "3.00", "4.00", "1.00"));
        givenNoBudget();
        givenNoSubscriptions();
        givenNoSnapshots();

        String block = builder(Duration.ofSeconds(5)).build(audience(CurrencyCode.USD), NOW);

        assertThat(block).contains("USD (yours)");
        assertThat(block).doesNotContain("CAD (yours)");
    }

    @Test
    @DisplayName("a section whose read fails is stated as unavailable, never reported as zero")
    void aFailedSectionIsUnavailableNotZero() {
        when(dashboard.today(any(), any())).thenThrow(new IllegalStateException("query blew up"));
        givenNoBudget();
        givenNoSubscriptions();
        givenNoSnapshots();

        String block = builder(Duration.ofSeconds(5)).build(audience(CurrencyCode.CAD), NOW);

        assertThat(block)
            .as("inventory D1's real damage was a confident 0.00: the coach told members they had "
                + "spent nothing because a read had failed. An unknown says it is unknown")
            .contains(CoachContextBuilder.UNAVAILABLE);
        assertThat(block)
            .as("and nothing anywhere substitutes a zero for it")
            .doesNotContain("spent 0.00");
    }

    @Test
    @DisplayName("a section that overruns the deadline is unavailable, and the rest still arrive")
    void aSlowSectionDoesNotSinkTheAnswer() {
        when(dashboard.today(any(), any())).thenAnswer(invocation -> {
            Thread.sleep(2_000);
            throw new IllegalStateException("never reached in time");
        });
        givenNoBudget();
        givenNoSubscriptions();
        givenSnapshot(CurrencyCode.CAD, "10000.00", "2500.00", "7500.00");

        String block = builder(Duration.ofMillis(150)).build(audience(CurrencyCode.CAD), NOW);

        assertThat(block).contains(CoachContextBuilder.UNAVAILABLE);
        assertThat(block)
            .as("the deadline bounds the whole fan-out, and whatever finished is still used")
            .contains("assets 10000.00, liabilities 2500.00, net worth 7500.00");
    }

    @Test
    @DisplayName("no budget for this month is a fact, not a failure")
    void aMissingBudgetIsNotAFailedRead() {
        givenDashboard(totals(CurrencyCode.CAD, "1.00", "2.00", "1.00"));
        when(periods.current(any(), any()))
            .thenThrow(new ResourceNotFoundException("No budget period"));
        givenNoSubscriptions();
        givenNoSnapshots();

        String block = builder(Duration.ofSeconds(5)).build(audience(CurrencyCode.CAD), NOW);

        assertThat(block)
            .as("'this household has not opened a budget' is true and useful; 'unavailable' would "
                + "be a lie about the read")
            .contains("(no budget period for this month)");
    }

    @Test
    @DisplayName("the block carries category names and aggregates, and nothing identifying")
    void theBlockCarriesTheMinimum() {
        givenDashboard(totals(CurrencyCode.CAD, "412.50", "3000.00", "2587.50"));
        BudgetPeriod period = new BudgetPeriod(HOUSEHOLD, USER, YearMonth.of(2026, 9),
            new BigDecimal("3000.00"), CurrencyCode.CAD, BudgetType.FLEXIBLE, NOW);
        when(periods.current(HOUSEHOLD, USER)).thenReturn(period);
        when(envelopes.list(any(), any(), any())).thenReturn(List.of(
            new EnvelopeResponse(UUID.randomUUID(), HOUSEHOLD, UUID.randomUUID(),
                UUID.randomUUID(), "Groceries", new BigDecimal("600.00"),
                new BigDecimal("412.50"), new BigDecimal("0.00"), CurrencyCode.CAD,
                new EnvelopeHealth(68, 55, true, new BigDecimal("187.50"),
                    new BigDecimal("330.00")),
                NOW, NOW)));
        when(subscriptions.summary(HOUSEHOLD, USER)).thenReturn(new SubscriptionSummaryResponse(
            CurrencyCode.CAD, 3, 1,
            List.of(new SubscriptionCurrencyTotals(CurrencyCode.CAD, new BigDecimal("57.99"),
                3, 1)),
            List.of()));
        givenSnapshot(CurrencyCode.CAD, "10000.00", "2500.00", "7500.00");

        String block = builder(Duration.ofSeconds(5)).build(audience(CurrencyCode.CAD), NOW);

        assertThat(block)
            .as("category names and aggregates are what a coach reasons about")
            .contains("Groceries: allocated 600.00, spent 412.50")
            .contains("monthly run rate 57.99, 3 active, 1 flagged")
            .contains("assets 10000.00, liabilities 2500.00, net worth 7500.00");
        assertThat(block)
            .as("the member's jurisdiction and timezone, from their own users row")
            .contains("Tax jurisdiction: CA")
            .contains("America/Toronto");
        assertThat(block)
            .as("nothing that identifies an account, an endpoint, a credential or another member "
                + "ever reaches a prompt")
            .doesNotContain(HOUSEHOLD.toString())
            .doesNotContain(USER.toString())
            .doesNotContain("http")
            .doesNotContain("@")
            .doesNotContain("sk-");
    }

    @Test
    @DisplayName("an unresolved template slot never reaches the model as text")
    void everySlotIsFilled() {
        givenDashboard(totals(CurrencyCode.CAD, "1.00", "2.00", "1.00"));
        givenNoBudget();
        givenNoSubscriptions();
        givenNoSnapshots();

        String block = builder(Duration.ofSeconds(5)).build(audience(CurrencyCode.CAD), NOW);

        assertThat(block)
            .as("a literal {{budget_summary}} in a prompt reads to a model as data")
            .doesNotContain("{{")
            .doesNotContain("}}");
    }

    @Test
    @DisplayName("a slot the values do not name is filled with the unavailable marker")
    void anUnknownSlotIsNotLeftInThePrompt() {
        assertThat(CoachContextBuilder.fill("a {{known}} and a {{mystery}}",
            java.util.Map.of("known", "value")))
            .isEqualTo("a value and a " + CoachContextBuilder.UNAVAILABLE);
    }

    @Test
    @DisplayName("a section the pool refuses is unavailable, not a 500 on the member's question")
    void aRejectedSectionIsUnavailable() throws InterruptedException {
        // The shipped coachExecutor's shape (AsyncConfig: bounded, ThreadPoolExecutor.AbortPolicy)
        // with every thread and every queue slot taken. Nothing is stubbed because nothing runs:
        // CompletableFuture.supplyAsync calls execute() inline, so the rejection is thrown on this
        // thread before the future it would have been attached to exists.
        ThreadPoolExecutor saturated = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        saturated.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            String block =
                builder(Duration.ofSeconds(5), saturated).build(audience(CurrencyCode.CAD), NOW);

            assertThat(occurrences(block, CoachContextBuilder.UNAVAILABLE))
                .as("AsyncConfig chose AbortPolicy on the promise that a rejection costs one "
                    + "block of one answer; if it escapes build() instead, the member's whole "
                    + "question is a 500 under exactly the load the policy exists for")
                .isEqualTo(4);
            verifyNoInteractions(dashboard, periods, envelopes, netWorth, subscriptions);
        } finally {
            release.countDown();
            saturated.shutdownNow();
        }
    }

    private static int occurrences(String text, String needle) {
        int found = 0;
        int cursor = text.indexOf(needle);
        while (cursor >= 0) {
            found++;
            cursor = text.indexOf(needle, cursor + needle.length());
        }
        return found;
    }

    private CoachContextBuilder builder(Duration deadline) {
        return builder(deadline, executor);
    }

    private CoachContextBuilder builder(Duration deadline, Executor pool) {
        return new CoachContextBuilder(dashboard, periods, envelopes, netWorth, subscriptions,
            prompts(), new CoachProperties(deadline, 16_000), pool);
    }

    private static CoachAudience audience(CurrencyCode currency) {
        return new CoachAudience(USER, HOUSEHOLD, "Sam", currency, Jurisdiction.CA,
            ZoneId.of("America/Toronto"));
    }

    /** The shipped template, so the test fails if a slot is renamed on one side only. */
    private static CoachPromptsProperties prompts() {
        return new CoachPromptsProperties("You are Trove.", """
            --- {{display_name}}, as of {{date}} ({{timezone}}) ---
            Tax jurisdiction: {{jurisdiction}}
            This month ({{month}}):
            {{month_summary}}
            Budget:
            {{budget_summary}}
            Subscriptions:
            {{subscription_summary}}
            Net worth:
            {{net_worth_summary}}
            """, 20, null);
    }

    private static DashboardCurrencyTotals totals(CurrencyCode currency, String spentThisMonth,
                                                  String incomeThisMonth, String netThisMonth) {
        return new DashboardCurrencyTotals(currency, new BigDecimal("0.00"), 0,
            new BigDecimal(spentThisMonth), new BigDecimal(incomeThisMonth),
            new BigDecimal(netThisMonth));
    }

    private void givenDashboard(DashboardCurrencyTotals... byCurrency) {
        when(dashboard.today(HOUSEHOLD, USER)).thenReturn(DashboardTodayResponse.of(
            LocalDate.of(2026, 9, 5), "America/Toronto", byCurrency[0], List.of(byCurrency)));
    }

    private void givenNoBudget() {
        when(periods.current(any(), any()))
            .thenThrow(new ResourceNotFoundException("No budget period"));
    }

    private void givenNoSubscriptions() {
        when(subscriptions.summary(any(), any())).thenReturn(new SubscriptionSummaryResponse(
            CurrencyCode.CAD, 0, 0, List.of(), List.of()));
    }

    private void givenNoSnapshots() {
        when(netWorth.getSeries(any(), any(), any()))
            .thenReturn(new NetWorthSeriesResponse(CurrencyCode.CAD, List.of()));
    }

    private void givenSnapshot(CurrencyCode currency, String assets, String liabilities,
                               String net) {
        when(netWorth.getSeries(any(), any(), any())).thenReturn(new NetWorthSeriesResponse(
            currency, List.of(new NetWorthSeries(currency, List.of(
                new NetWorthPoint(LocalDate.of(2026, 9, 4), new BigDecimal(assets),
                    new BigDecimal(liabilities), new BigDecimal(net)))))));
    }
}
