package finance.trove.budgets.rollover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.budgets.envelope.Envelope;
import finance.trove.budgets.envelope.EnvelopeRepository;
import finance.trove.budgets.envelope.EnvelopeSpendService;
import finance.trove.budgets.period.BudgetPeriod;
import finance.trove.budgets.period.BudgetPeriodRepository;
import finance.trove.budgets.period.BudgetType;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link RolloverService} — the carry-forward arithmetic, and the regression gate for finding D1.
 *
 * <p>The pre-collapse service's own arithmetic tests were honest: they asserted
 * {@code allocated + rollover - spent} against an assumed {@code spent} and passed. What no test in
 * that module could see was that nothing in the platform ever wrote {@code spent}, so the real
 * carry was always the full allocation and compounded month on month without bound.
 * {@link #aFullySpentEnvelopeCarriesNothing} is the test that fails without a spend writer.
 */
@ExtendWith(MockitoExtension.class)
class RolloverServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID GROCERIES = UUID.randomUUID();
    private static final UUID SOURCE_PERIOD = UUID.randomUUID();
    private static final UUID TARGET_PERIOD = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T00:40:00Z");
    private static final LocalDate OCTOBER_FIRST = LocalDate.of(2026, 10, 1);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);
    private static final YearMonth OCTOBER = YearMonth.of(2026, 10);

    @Mock
    private BudgetPeriodRepository periods;

    @Mock
    private EnvelopeRepository envelopes;

    @Mock
    private EnvelopeSpendService spend;

    private final UserTimeZoneProvider zones = userId -> ZoneOffset.UTC;

    private RolloverService service() {
        return new RolloverService(periods, envelopes, spend,
            new UserClock(zones, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static BudgetPeriod september() {
        return new BudgetPeriod(HOUSEHOLD, MEMBER, SEPTEMBER, new BigDecimal("5000.00"),
            CurrencyCode.CAD, BudgetType.ROLLOVER, Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static BudgetPeriod october() {
        return new BudgetPeriod(HOUSEHOLD, MEMBER, OCTOBER, new BigDecimal("5000.00"),
            CurrencyCode.CAD, BudgetType.ROLLOVER, NOW);
    }

    private static Envelope envelope(String allocated, String spent, String rollover) {
        Envelope envelope = new Envelope(HOUSEHOLD, SOURCE_PERIOD, GROCERIES,
            new BigDecimal(allocated), CurrencyCode.CAD, Instant.parse("2026-09-01T00:00:00Z"));
        envelope.rewriteSpent(new BigDecimal(spent));
        envelope.setRolloverAmount(new BigDecimal(rollover));
        return envelope;
    }

    private void sourceIs(BudgetPeriod source, Envelope... sourceEnvelopes) {
        when(periods.findByIdAndHouseholdId(SOURCE_PERIOD, HOUSEHOLD))
            .thenReturn(Optional.of(source));
        when(envelopes.findAllByHouseholdIdAndPeriodIdOrderByCreatedAtAsc(eq(HOUSEHOLD), any()))
            .thenReturn(List.of(sourceEnvelopes));
    }

    private void periodSavesPassThrough() {
        when(periods.save(any(BudgetPeriod.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void envelopeSavesPassThrough() {
        when(envelopes.save(any(Envelope.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("the carry is allocated + previous rollover - spent")
    void theCarryIsTheUnspentBalance() {
        sourceIs(september(), envelope("400.00", "250.00", "25.00"));
        periodSavesPassThrough();
        envelopeSavesPassThrough();
        when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, OCTOBER_FIRST))
            .thenReturn(Optional.empty());
        when(envelopes.findByPeriodIdAndCategoryId(any(), eq(GROCERIES)))
            .thenReturn(Optional.empty());

        assertThat(service().rollForward(HOUSEHOLD, SOURCE_PERIOD)).isEqualTo(1);

        verify(envelopes).save(argThat(saved ->
            saved.getRolloverAmount().compareTo(new BigDecimal("175.00")) == 0));
    }

    @Test
    @DisplayName("D1 REGRESSION GATE: a fully spent envelope carries NOTHING forward")
    void aFullySpentEnvelopeCarriesNothing() {
        // This is the test that fails without a spend writer. With `spent` pinned at zero — the
        // pre-collapse state — an envelope that spent its whole allocation carried the whole
        // allocation forward, and did so again the month after, and the month after that.
        BudgetPeriod source = september();
        sourceIs(source, envelope("400.00", "400.00", "0.00"));
        periodSavesPassThrough();
        when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, OCTOBER_FIRST))
            .thenReturn(Optional.empty());

        assertThat(service().rollForward(HOUSEHOLD, SOURCE_PERIOD))
            .as("nothing is left over and October has no envelope for it yet, so there is nothing "
                + "to carry and nothing to create")
            .isZero();

        verify(envelopes, never()).save(any(Envelope.class));
        verify(periods, never()).save(argThat(saved -> saved.month().equals(OCTOBER)));
        assertThat(source.getRolledOverAt())
            .as("the period is still marked done — there was genuinely nothing owed, and leaving "
                + "it unmarked would make the job revisit it every day for ever")
            .isEqualTo(NOW);
    }

    @Test
    @DisplayName("gap P2-39: the floor at zero forgives overspend rather than carrying a debt")
    void theFloorForgivesOverspend() {
        // Recorded, not fixed. Gap P2-39 proposes a per-budget overspend policy (forgive vs.
        // deduct) with a visible note; that is a product decision and is SHOULD-phase. This test
        // pins today's behaviour so that a later step changes it deliberately or not at all.
        Envelope octoberEnvelope = new Envelope(HOUSEHOLD, TARGET_PERIOD, GROCERIES,
            new BigDecimal("400.00"), CurrencyCode.CAD, NOW);
        sourceIs(september(), envelope("400.00", "650.00", "0.00"));
        periodSavesPassThrough();
        envelopeSavesPassThrough();
        when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, OCTOBER_FIRST))
            .thenReturn(Optional.of(october()));
        when(envelopes.findByPeriodIdAndCategoryId(any(), eq(GROCERIES)))
            .thenReturn(Optional.of(octoberEnvelope));

        service().rollForward(HOUSEHOLD, SOURCE_PERIOD);

        assertThat(octoberEnvelope.getRolloverAmount())
            .as("250.00 overspent carries as 0.00, never as -250.00: the household is forgiven "
                + "the overspend silently, which gap P2-39 names and this step defers")
            .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("the carry is SET on the target, so a repeated pass cannot compound it")
    void theCarryIsSetAndNotAdded() {
        Envelope octoberEnvelope = new Envelope(HOUSEHOLD, TARGET_PERIOD, GROCERIES,
            new BigDecimal("400.00"), CurrencyCode.CAD, NOW);
        octoberEnvelope.setRolloverAmount(new BigDecimal("300.00"));
        sourceIs(september(), envelope("400.00", "100.00", "0.00"));
        periodSavesPassThrough();
        envelopeSavesPassThrough();
        when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, OCTOBER_FIRST))
            .thenReturn(Optional.of(october()));
        when(envelopes.findByPeriodIdAndCategoryId(any(), eq(GROCERIES)))
            .thenReturn(Optional.of(octoberEnvelope));

        service().rollForward(HOUSEHOLD, SOURCE_PERIOD);

        assertThat(octoberEnvelope.getRolloverAmount())
            .as("300.00 was already there from an earlier attempt; the answer is 300.00 replaced "
                + "by 300.00, not 600.00 — one source period carries into one target exactly once")
            .isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    @DisplayName("a period already marked rolled over is skipped entirely")
    void anAlreadyRolledPeriodIsANoOp() {
        BudgetPeriod source = september();
        source.markRolledOver(Instant.parse("2026-10-01T00:40:00Z"));
        when(periods.findByIdAndHouseholdId(SOURCE_PERIOD, HOUSEHOLD))
            .thenReturn(Optional.of(source));

        assertThat(service().rollForward(HOUSEHOLD, SOURCE_PERIOD))
            .as("a second run in the same window changes nothing — that is what makes the daily "
                + "cron safe")
            .isZero();

        verify(envelopes, never()).save(any(Envelope.class));
        verify(periods, never()).save(any(BudgetPeriod.class));
    }

    @Test
    @DisplayName("the target period is created from the source's own settings when it is missing")
    void theTargetPeriodIsCreatedWhenTheHouseholdHasNotOpenedItYet() {
        sourceIs(september(), envelope("400.00", "100.00", "0.00"));
        periodSavesPassThrough();
        envelopeSavesPassThrough();
        when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, OCTOBER_FIRST))
            .thenReturn(Optional.empty());
        when(envelopes.findByPeriodIdAndCategoryId(any(), eq(GROCERIES)))
            .thenReturn(Optional.empty());

        service().rollForward(HOUSEHOLD, SOURCE_PERIOD);

        verify(periods).save(argThat(saved ->
            saved.month().equals(OCTOBER)
                && saved.getBudgetType() == BudgetType.ROLLOVER
                && saved.getCurrency() == CurrencyCode.CAD
                && saved.getUserId().equals(MEMBER)
                && saved.getTotalIncome().compareTo(new BigDecimal("5000.00")) == 0));
    }

    @Test
    @DisplayName("the new month's envelope is recomputed from the ledger, not left at zero")
    void theCarriedEnvelopeIsRecomputedFromTheLedger() {
        sourceIs(september(), envelope("400.00", "100.00", "0.00"));
        periodSavesPassThrough();
        envelopeSavesPassThrough();
        when(periods.findByHouseholdIdAndMonth(HOUSEHOLD, OCTOBER_FIRST))
            .thenReturn(Optional.empty());
        when(envelopes.findByPeriodIdAndCategoryId(any(), eq(GROCERIES)))
            .thenReturn(Optional.empty());

        service().rollForward(HOUSEHOLD, SOURCE_PERIOD);

        verify(spend).recalculate(HOUSEHOLD, MEMBER, GROCERIES, OCTOBER);
    }
}
