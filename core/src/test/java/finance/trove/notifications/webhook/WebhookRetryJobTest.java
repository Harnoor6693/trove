package finance.trove.notifications.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The retry job's schedule, which §5 item 6 fixes and which nothing else asserts by name.
 *
 * <p>{@code ScheduledJobsTest} proves every job in the application has a lock, a unique name and a
 * UTC cron; it cannot prove that THIS job's numbers are the ones the plan chose, and the two that
 * matter are subtle: {@code lockAtMostFor} one second under the 30-second period, so a pass that
 * dies without releasing does not stop the queue for ever, and a {@code lockAtLeastFor} that keeps
 * a fast empty pass from being immediately re-run by a second instance whose clock is a little
 * ahead.
 */
@ExtendWith(MockitoExtension.class)
class WebhookRetryJobTest {

    @Mock
    private WebhookDeliveryService deliveries;

    @Test
    @DisplayName("One pass delegates to deliverDue and reports nothing of its own")
    void thePassDelegates() {
        when(deliveries.deliverDue()).thenReturn(3);

        new WebhookRetryJob(deliveries).retryDueDeliveries();

        verify(deliveries).deliverDue();
    }

    @Test
    @DisplayName("An empty pass is not an error")
    void anEmptyPassIsFine() {
        when(deliveries.deliverDue()).thenReturn(0);

        new WebhookRetryJob(deliveries).retryDueDeliveries();

        verify(deliveries).deliverDue();
    }

    @Test
    @DisplayName("The schedule is the one §5 item 6 fixes, and the lock is the source's name")
    void theScheduleIsTheOneThePlanNames() throws Exception {
        var method = WebhookRetryJob.class.getMethod("retryDueDeliveries");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

        assertThat(scheduled.fixedDelay()).isEqualTo(30_000L);
        assertThat(scheduled.cron())
            .as("a fixed delay has no calendar to be wrong about, so no zone applies")
            .isEmpty();
        assertThat(lock.name()).isEqualTo("webhook-retry");
        assertThat(lock.lockAtMostFor())
            .as("one second under the period: a pass that dies without releasing must not stop "
                + "the queue for ever")
            .isEqualTo("PT29S");
        assertThat(lock.lockAtLeastFor()).isEqualTo("PT5S");
    }
}
