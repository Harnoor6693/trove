package finance.trove.ai.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.ai.AiEndpointUnauthorizedException;
import finance.trove.ai.AiPolicyBlockedException;
import finance.trove.ai.chat.CoachAudience;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.common.time.UserClock;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The loop's contract: everybody eligible is visited, one member's failure never stops the run, and
 * a shutdown stops it at a member boundary ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5 items 6
 * and 13).
 *
 * <p>{@code ScheduledJobsTest} already scans every compiled class for a duplicate lock name, a
 * missing lock or a cron without {@code zone = "UTC"}, so this class does not repeat that sweep —
 * it pins the two values §5 item 6 fixes for THIS job, which that sweep cannot know.
 */
class WeeklyInsightJobTest {

    private static final Instant RUN_AT = Instant.parse("2026-09-07T08:30:00Z");

    private final InsightRecipients recipients = mock(InsightRecipients.class);
    private final InsightService insights = mock(InsightService.class);
    private final UserClock clock = mock(UserClock.class);

    @Test
    @DisplayName("the cron and the lock are the ones §5 item 6 fixes")
    void theScheduleIsPinned() throws NoSuchMethodException {
        Method job = WeeklyInsightJob.class.getMethod("generateWeeklyInsights");

        assertThat(job.getAnnotation(Scheduled.class).cron()).isEqualTo("0 30 8 * * MON");
        assertThat(job.getAnnotation(Scheduled.class).zone())
            .as("the TRIGGER is the container's UTC clock; the CONTENT is each recipient's own "
                + "week (§5.6, gap row 15)")
            .isEqualTo("UTC");
        assertThat(job.getAnnotation(SchedulerLock.class).name())
            .isEqualTo(WeeklyInsightJob.LOCK_NAME)
            .isEqualTo("weekly-insight");
        assertThat(job.getAnnotation(SchedulerLock.class).lockAtMostFor()).isEqualTo("PT30M");
    }

    @Test
    @DisplayName("every recipient is visited, page after page")
    void everyRecipientIsVisited() {
        List<CoachAudience> firstPage = audiences(WeeklyInsightJob.PAGE_SIZE);
        List<CoachAudience> secondPage = audiences(3);
        when(recipients.page(0, WeeklyInsightJob.PAGE_SIZE)).thenReturn(firstPage);
        when(recipients.page(WeeklyInsightJob.PAGE_SIZE, WeeklyInsightJob.PAGE_SIZE))
            .thenReturn(secondPage);
        when(insights.generateFor(any(), any())).thenReturn(true);

        WeeklyInsightJob.Result result = job().runOnce(RUN_AT);

        assertThat(result.considered()).isEqualTo(WeeklyInsightJob.PAGE_SIZE + 3);
        assertThat(result.written()).isEqualTo(WeeklyInsightJob.PAGE_SIZE + 3);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("one member's endpoint failing never stops the rest of the run")
    void oneFailureDoesNotStopTheLoop() {
        List<CoachAudience> page = audiences(3);
        when(recipients.page(eq(0), anyInt())).thenReturn(page);
        when(insights.generateFor(eq(page.get(0)), any())).thenReturn(true);
        when(insights.generateFor(eq(page.get(1)), any()))
            .thenThrow(new AiEndpointUnauthorizedException("their key expired"));
        when(insights.generateFor(eq(page.get(2)), any()))
            .thenThrow(new AiPolicyBlockedException("their tier refuses it"));

        WeeklyInsightJob.Result result = job().runOnce(RUN_AT);

        assertThat(result.considered()).isEqualTo(3);
        assertThat(result.written())
            .as("the member before the failures still got their note")
            .isEqualTo(1);
        assertThat(result.failed())
            .as("one member's endpoint being down is that member's situation, recorded and "
                + "stepped over, not an outage of the job")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("an unexpected failure is recorded and stepped over too")
    void anUnexpectedFailureIsAlsoSurvived() {
        List<CoachAudience> page = audiences(2);
        when(recipients.page(eq(0), anyInt())).thenReturn(page);
        when(insights.generateFor(eq(page.get(0)), any()))
            .thenThrow(new IllegalStateException("a bug"));
        when(insights.generateFor(eq(page.get(1)), any())).thenReturn(true);

        WeeklyInsightJob.Result result = job().runOnce(RUN_AT);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.written()).isEqualTo(1);
    }

    @Test
    @DisplayName("a member who already had a note is skipped, not written and not failed")
    void aDuplicateIsSkipped() {
        when(recipients.page(eq(0), anyInt())).thenReturn(audiences(2));
        when(insights.generateFor(any(), any())).thenReturn(false);

        WeeklyInsightJob.Result result = job().runOnce(RUN_AT);

        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.written()).isZero();
    }

    @Test
    @DisplayName("a graceful shutdown stops the run at a member boundary")
    void interruptionStopsTheLoopBetweenRecipients() throws InterruptedException {
        List<CoachAudience> page = audiences(5);
        when(recipients.page(eq(0), anyInt())).thenReturn(page);
        when(insights.generateFor(any(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return true;
        });

        AtomicReference<WeeklyInsightJob.Result> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(job().runOnce(RUN_AT)));
        worker.start();
        worker.join(5_000);

        assertThat(result.get()).isNotNull();
        assertThat(result.get().considered())
            .as("the check is between recipients, so exactly one generation finished before the "
                + "loop noticed (§5 item 13)")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("the scheduled entry point resolves 'now' from the clock, not from the wall")
    void theScheduledEntryPointUsesTheClock() {
        when(clock.now()).thenReturn(RUN_AT);
        when(recipients.page(eq(0), anyInt())).thenReturn(audiences(1));
        when(insights.generateFor(any(), any())).thenReturn(true);

        job().generateWeeklyInsights();

        verify(insights).generateFor(any(), eq(RUN_AT));
    }

    private WeeklyInsightJob job() {
        return new WeeklyInsightJob(recipients, insights, clock);
    }

    private static List<CoachAudience> audiences(int count) {
        List<CoachAudience> audiences = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            audiences.add(new CoachAudience(UUID.randomUUID(), UUID.randomUUID(), "Member " + i,
                CurrencyCode.CAD, Jurisdiction.CA, ZoneId.of("America/Toronto")));
        }
        return audiences;
    }
}
