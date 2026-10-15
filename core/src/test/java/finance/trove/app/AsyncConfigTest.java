package finance.trove.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The two properties of the background executor that are easy to lose and hard to notice: the
 * diagnostic context crossing the thread boundary, and the pool being bounded.
 */
class AsyncConfigTest {

    private final AsyncConfig config = new AsyncConfig();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("The request id follows the work onto the background thread")
    void theMdcIsPropagated() {
        MDC.put("requestId", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
        Map<String, String> seen = new HashMap<>();

        Runnable decorated = config.mdcPropagating()
            .decorate(() -> seen.put("requestId", MDC.get("requestId")));
        MDC.clear();
        decorated.run();

        assertThat(seen.get("requestId"))
            .as("without this an audit-write failure is logged against no request at all, and "
                + "the id in the client's error report matches nothing in the log")
            .isEqualTo("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
    }

    @Test
    @DisplayName("The context is cleared afterwards, never left on the pooled thread")
    void theMdcIsClearedAfterwards() {
        MDC.put("requestId", "first");
        Runnable decorated = config.mdcPropagating().decorate(() -> { });
        decorated.run();

        assertThat(MDC.get("requestId"))
            .as("a leaked id on a pooled thread is attributed to the next, unrelated task")
            .isNull();
    }

    @Test
    @DisplayName("The context is cleared even when the task throws")
    void theMdcIsClearedOnFailure() {
        MDC.put("requestId", "first");
        Runnable decorated = config.mdcPropagating().decorate(() -> {
            throw new IllegalStateException("boom");
        });

        try {
            decorated.run();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("boom");
        }
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    @DisplayName("The audit executor is bounded and discards rather than running on the caller")
    void theAuditExecutorIsBounded() {
        TaskDecorator decorator = config.mdcPropagating();
        Executor executor = config.auditExecutor(decorator);

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;
        assertThat(pool.getMaxPoolSize())
            .as("audit writes are downstream of a response that has already been sent; they need "
                + "throughput, not a pool")
            .isEqualTo(2);
        assertThat(pool.getThreadPoolExecutor().getRejectedExecutionHandler())
            .as("CallerRunsPolicy here means the HTTP thread pays for the insert once the queue "
                + "fills — §5.7's objection, and worse in a filter's finally block")
            .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy.class);
        pool.shutdown();
    }

    @Test
    @DisplayName("There is no application-wide default executor")
    void noDefaultExecutor() {
        assertThat(config.getAsyncExecutor())
            .as("an unqualified @Async would otherwise land in a pool sized for something else, "
                + "and its caller would have no way to know which")
            .isNull();
        assertThat(config.getAsyncUncaughtExceptionHandler()).isNotNull();
    }

    @Test
    @DisplayName("An exception from a void @Async is reported rather than swallowed by the pool")
    void uncaughtAsyncExceptionsAreReported() throws Exception {
        java.lang.reflect.Method method = AsyncConfigTest.class.getDeclaredMethod("clearMdc");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
            config.getAsyncUncaughtExceptionHandler()
                .handleUncaughtException(new IllegalStateException("boom"), method)))
            .as("without a handler the work simply never happened, silently — there is no caller "
                + "left holding a Future to notice")
            .isNull();
    }
}
