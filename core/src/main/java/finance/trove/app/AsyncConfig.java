package finance.trove.app;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The monolith's only {@code @EnableAsync} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5.7).
 *
 * <h2>Named executors only</h2>
 * There is no default executor bean here on purpose: an unqualified {@code @Async} would then fall
 * back to Boot's {@code applicationTaskExecutor}, which is unbounded in queue length, and the
 * caller would have no idea which pool it landed in. {@link AsyncConfigurer#getAsyncExecutor()}
 * returns null below, which makes an unqualified {@code @Async} use the framework default and
 * makes the qualifier the thing that decides — every {@code @Async} in this application names its
 * executor.
 *
 * <h2>Bounded, and the rejection policy is the design</h2>
 * {@link #AUDIT_EXECUTOR} is 1 core / 2 max / 1000 queued with
 * {@link ThreadPoolExecutor.DiscardOldestPolicy}. Under a flood the oldest queued audit row is
 * dropped rather than either blocking a request thread or running a database insert on it —
 * §5.7's objection to {@code CallerRunsPolicy} applies with full force to a filter's
 * {@code finally} block, where "caller runs" means the HTTP thread pays for the insert and the
 * response the row describes has already been sent. Dropping the oldest also loses the least
 * interesting rows first: under a burst it is the shape and the end of the burst that matter.
 *
 * <h2>MDC crosses the thread boundary</h2>
 * {@link #mdcPropagating()} copies the calling thread's diagnostic context onto the task, so the
 * {@code requestId} of the request that queued the work appears on every line the background work
 * logs — without it an audit-write failure is logged against no request at all, and the one thing
 * an operator has to go on (the id in the client's error report) matches nothing.
 * The context is <strong>cleared</strong> afterwards, not restored: these are pooled threads, and
 * a leaked id would be attributed to the next, unrelated task.
 *
 * <h2>Two rejection policies, because the two kinds of work differ</h2>
 * {@link #EXPORT_EXECUTOR} is 2 core / 4 max / 50 queued with
 * {@link ThreadPoolExecutor.AbortPolicy} (§5.7, collapse step 15). The rejection is the design:
 * it comes back to {@code TaxExportService.request} as a {@code TaskRejectedException} on the
 * request thread, which flips the already-committed run row to {@code FAILED} before the response
 * is sent — so a member gets a real id with an honest status rather than a run that never moves.
 * {@code CallerRunsPolicy} was rejected outright, as the source {@code ReportAsyncConfig} argued:
 * "caller runs" here means a whole year of a household's ledger is rendered to CSV or PDF on the
 * HTTP thread, which turns the export endpoint into a thread-exhaustion vector for any
 * authenticated member.
 *
 * <p>{@code receiptExecutor} is registered below by collapse step 14, which added the work it
 * carries, and its {@code AbortPolicy} carries the sharpest version of that argument: with
 * {@code CallerRunsPolicy} — which the source's {@code ReceiptAsyncConfig} used — a saturated
 * queue puts a two-minute vision call on the HTTP thread of the next upload, so any authenticated
 * member could exhaust the servlet pool by uploading receipts faster than they process.
 * ({@code coachExecutor} was attributed to step 12 in an earlier revision of this note; the coach
 * is collapse step 13, step 12 landed the AI gateway without it, and every pool is registered by
 * the step that added its work.)
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    /** Qualifier for the audit-row executor; see {@code AuditLogWriter}. */
    public static final String AUDIT_EXECUTOR = "auditExecutor";

    /**
     * Qualifier for the tax-export generation pool; see
     * {@code finance.trove.taxexport.TaxExportGenerationService} (§5.7, collapse step 15).
     */
    public static final String EXPORT_EXECUTOR = "exportExecutor";

    /**
     * Qualifier for the coach's context fan-out; see
     * {@code finance.trove.ai.chat.CoachContextBuilder} (§5.7, collapse step 13).
     */
    public static final String COACH_EXECUTOR = "coachExecutor";

    /**
     * Qualifier for the receipt pipeline; see
     * {@code finance.trove.receipts.pipeline.ReceiptPipelineOrchestrator} (§5.7, collapse
     * step 14).
     */
    public static final String RECEIPT_EXECUTOR = "receiptExecutor";

    private static final Logger LOG = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean(name = AUDIT_EXECUTOR)
    public Executor auditExecutor(TaskDecorator mdcPropagating) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("trove-audit-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardOldestPolicy());
        executor.setTaskDecorator(mdcPropagating);
        // Let queued audit rows finish on shutdown, but never hold the JVM open for them:
        // server.shutdown=graceful already waits for in-flight requests, and this pool's work is
        // strictly downstream of those (gap P1-2).
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }

    /**
     * The tax-export generation pool (§5.7 item 7, collapse step 15).
     *
     * <p>2 core / 4 max / 50 queued: generation is CPU- and memory-bound (a year of a ledger
     * rendered to CSV or PDF in memory) rather than latency-bound, so a wide pool would only
     * multiply peak heap on a 2 GB container. The queue is short for the same reason —
     * fifty pending exports on one install is already a signal, and the fifty-first is better
     * told so than left in a queue nobody can see.
     *
     * <p>{@link ThreadPoolExecutor.AbortPolicy} rather than {@code CallerRunsPolicy}: see the
     * class javadoc. The exception reaches the request thread, which records the run as
     * {@code FAILED} synchronously before answering.
     *
     * <p>{@code waitForTasksToCompleteOnShutdown} is false here, unlike the audit pool: a
     * half-finished export is worth nothing and holding the JVM open for one delays a deployment.
     * The run stays {@code GENERATING} and the member can start another (an export-retention and
     * stuck-run sweep is deferred — §5 item 6 lists no ShedLock name for one).
     */
    @Bean(name = EXPORT_EXECUTOR)
    public Executor exportExecutor(TaskDecorator mdcPropagating) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("trove-export-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(mdcPropagating);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * The coach's context fan-out pool (§5.7 item 7, collapse step 13).
     *
     * <p>4 core / 8 max / 50 queued, the figures §5.7 names. Wider than the export pool and much
     * shorter-queued than the audit one because the work is a different shape: four small
     * read-only aggregate queries per question, each of which spends nearly all of its time
     * waiting on Postgres rather than on a CPU, and all four of which a member is sitting in front
     * of. Four concurrent questions is already a busy single-household install, and eight threads
     * is two of them fanned out at once.
     *
     * <p><strong>{@link ThreadPoolExecutor.AbortPolicy}, deliberately, and it is visible to
     * nobody.</strong> The three candidates fail differently here. {@code CallerRunsPolicy} would
     * run the four queries serially on the thread that is already holding an open SSE response —
     * turning a saturated pool into a slower answer for everyone rather than a bounded one, which
     * is §5.7's objection with the roles reversed. {@code DiscardOldestPolicy} would silently drop
     * a section that another member is waiting on. Aborting rejects the section, and
     * {@code CoachContextBuilder} already has exactly one behaviour for a section it could not
     * read: it says so in the prompt. So a rejection degrades one block of one answer to
     * "unavailable" — honest, bounded, and never a failed question. This is the one pool whose
     * rejection is not an error path at all.
     *
     * <p>{@code waitForTasksToCompleteOnShutdown} is false: a context section whose answer nobody
     * is left to read is worth nothing, and holding the JVM open for one delays a deployment while
     * {@code server.shutdown=graceful} is already draining the request it belonged to.
     */
    @Bean(name = COACH_EXECUTOR)
    public Executor coachExecutor(TaskDecorator mdcPropagating) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("trove-coach-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(mdcPropagating);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * The receipt-processing pool (§5.7 item 7, collapse step 14).
     *
     * <p>3 core / 6 max / 100 queued, the figures §5.7 names. The work is one receipt's whole
     * pipeline, and its cost is dominated by a single vision call against somebody's own model —
     * seconds at best and up to two minutes at worst. A narrow pool is right for that: the
     * bottleneck is the member's endpoint, not this container, and six concurrent inferences is
     * already more than any self-hosted model will serve. The queue is long, unlike the export
     * pool's, because a burst of receipts is a member emptying their wallet after a trip and every
     * one of them is worth doing — just not all at once.
     *
     * <p><strong>{@link ThreadPoolExecutor.AbortPolicy}, and this is the one where
     * {@code CallerRunsPolicy} was actively dangerous.</strong> The source's
     * {@code ReceiptAsyncConfig} used it. "Caller runs" here means that once a hundred receipts are
     * queued, the whole pipeline — including a 120-second vision call to a remote endpoint — runs
     * on the HTTP request thread of the next upload. Any authenticated member could then occupy
     * every servlet thread in the container by uploading receipts faster than they process, which
     * is a thread-exhaustion vector rather than a slow response. Aborting instead throws
     * {@code TaskRejectedException} back to {@code ReceiptUploadService}, which has already
     * committed the row and both files: it answers 202 with the receipt in {@code UPLOADED} and the
     * hourly {@code receipt-orphan-sweep} re-enqueues it. Nothing is lost and no request thread
     * pays for a model's inference.
     *
     * <p>{@code waitForTasksToCompleteOnShutdown} is false, like the export and coach pools: a
     * half-processed receipt is worth nothing, holding the JVM open for one delays a deployment,
     * and the row stays non-terminal for the sweep to pick up — which is the same path a rejection
     * takes.
     */
    @Bean(name = RECEIPT_EXECUTOR)
    public Executor receiptExecutor(TaskDecorator mdcPropagating) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("trove-receipt-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setTaskDecorator(mdcPropagating);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /** Carries {@code requestId} and {@code userId} onto the background thread. */
    @Bean
    public TaskDecorator mdcPropagating() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }

    /**
     * No application-wide default executor: {@code @Async} without a qualifier is a mistake this
     * application does not make, and returning null here leaves the framework's own default in
     * place rather than silently routing unqualified work into a pool sized for something else.
     */
    @Override
    public Executor getAsyncExecutor() {
        return null;
    }

    /**
     * An exception thrown by a {@code void @Async} method has nowhere to go; without this it is
     * swallowed by the executor and the work simply never happened, silently.
     */
    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
        getAsyncUncaughtExceptionHandler() {
        return (throwable, method, params) ->
            LOG.error("Uncaught exception from @Async {}.{}", method.getDeclaringClass().getName(),
                method.getName(), throwable);
    }
}
