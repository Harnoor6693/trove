package finance.trove.budgets.rollover;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link EnvelopeRolloverJob} — which periods a pass selects, and why.
 *
 * <p>The two things worth asserting here are the two the pre-collapse {@code RolloverScheduler} got
 * wrong: it fired once a month and derived everything from {@code LocalDate.now()}, so a container
 * that was down at 01:00 on the first silently cost every household that month's carry for ever
 * (gap P2-39), and it resolved the boundary in server time rather than in any member's own
 * (gap P2-15).
 */
@ExtendWith(MockitoExtension.class)
class EnvelopeRolloverJobTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final ZoneId VANCOUVER = ZoneId.of("America/Vancouver");
    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");

    private final UUID vancouverMember = UUID.randomUUID();
    private final UUID torontoMember = UUID.randomUUID();

    private final UserTimeZoneProvider zones = userId -> Map.of(
        vancouverMember, VANCOUVER,
        torontoMember, TORONTO).getOrDefault(userId, ZoneOffset.UTC);

    @Mock
    private BudgetPeriodRepository periods;

    @Mock
    private RolloverService rollover;

    private EnvelopeRolloverJob job(Instant now) {
        return new EnvelopeRolloverJob(periods, rollover,
            new UserClock(zones, Clock.fixed(now, ZoneOffset.UTC)));
    }

    private static BudgetPeriod period(UUID creator, YearMonth month) {
        return new BudgetPeriod(HOUSEHOLD, creator, month, new BigDecimal("5000.00"),
            CurrencyCode.CAD, BudgetType.ROLLOVER, Instant.parse("2026-09-01T00:00:00Z"));
    }

    @Test
    @DisplayName("the lock name is the one §5.6 item 6 assigns")
    void theLockNameIsFixed() {
        assertThat(EnvelopeRolloverJob.LOCK_NAME)
            .as("ScheduledJobsTest fails the build on a duplicate, but only the plan says which "
                + "name this job must take")
            .isEqualTo("envelope-rollover");
    }

    @Test
    @DisplayName("a period whose month has ended for its creator is carried forward")
    void anEndedMonthIsCarried() {
        BudgetPeriod september = period(torontoMember, YearMonth.of(2026, 9));
        when(periods.findRolloverCandidates(LocalDate.of(2026, 10, 1)))
            .thenReturn(List.of(september));
        when(rollover.rollForward(eq(HOUSEHOLD), any())).thenReturn(2);

        // 2026-10-01T04:30Z — already the 1st of October in Toronto.
        assertThat(job(Instant.parse("2026-10-01T04:30:00Z")).runOnce()).isEqualTo(2);
    }

    @Test
    @DisplayName("gap P2-15: the same instant does not end the month for a west-coast member")
    void theBoundaryIsTheCreatorsOwn() {
        BudgetPeriod september = period(vancouverMember, YearMonth.of(2026, 9));
        when(periods.findRolloverCandidates(LocalDate.of(2026, 10, 1)))
            .thenReturn(List.of(september));

        // The very same instant: 21:30 on the 30th of September in Vancouver.
        assertThat(job(Instant.parse("2026-10-01T04:30:00Z")).runOnce()).isZero();

        verify(rollover, never()).rollForward(any(), any());
    }

    @Test
    @DisplayName("gap P2-39: a month missed while the container was down is still carried")
    void aMissedMonthIsPickedUpLater() {
        // Three months late. The source's cron fired once, on the 1st, and derived its work from
        // today's date — so a container that was down that minute lost the carry permanently.
        // Here the marker is null, so the work is simply still outstanding.
        BudgetPeriod june = period(torontoMember, YearMonth.of(2026, 6));
        BudgetPeriod july = period(torontoMember, YearMonth.of(2026, 7));
        when(periods.findRolloverCandidates(LocalDate.of(2026, 9, 1)))
            .thenReturn(List.of(june, july));
        when(rollover.rollForward(eq(HOUSEHOLD), any())).thenReturn(1);

        assertThat(job(Instant.parse("2026-09-17T00:40:00Z")).runOnce())
            .as("both outstanding months are carried in one pass, oldest first, so each month's "
                + "carry lands on the period that follows it")
            .isEqualTo(2);
        verify(rollover, org.mockito.Mockito.times(2)).rollForward(eq(HOUSEHOLD), any());
    }

    @Test
    @DisplayName("one household's failure does not stop the rest of the pass")
    void oneFailureDoesNotStopThePass() {
        BudgetPeriod first = period(torontoMember, YearMonth.of(2026, 8));
        BudgetPeriod second = period(torontoMember, YearMonth.of(2026, 9));
        when(periods.findRolloverCandidates(LocalDate.of(2026, 10, 1)))
            .thenReturn(List.of(first, second));
        // Consecutive stubbing rather than per-id, because neither fixture has been through the
        // database and so both ids are null.
        when(rollover.rollForward(eq(HOUSEHOLD), any()))
            .thenThrow(new IllegalStateException("boom"))
            .thenReturn(3);

        assertThat(job(Instant.parse("2026-10-01T04:30:00Z")).runOnce())
            .as("the marker is only written on success, so the failed period is simply picked up "
                + "again tomorrow")
            .isEqualTo(3);
    }

    @Test
    @DisplayName("a period whose creator has been deleted falls back to UTC rather than stalling")
    void aDeletedCreatorFallsBackToUtc() {
        BudgetPeriod orphaned = period(null, YearMonth.of(2026, 9));
        when(periods.findRolloverCandidates(LocalDate.of(2026, 10, 1)))
            .thenReturn(List.of(orphaned));
        when(rollover.rollForward(eq(HOUSEHOLD), any())).thenReturn(1);

        assertThat(job(Instant.parse("2026-10-01T04:30:00Z")).runOnce())
            .as("user_id is ON DELETE SET NULL; the household's budget must still roll forward "
                + "when the member who opened it has left")
            .isEqualTo(1);
    }
}
