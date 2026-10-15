package finance.trove.notifications.webhook;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the webhook delivery queue ({@code docs/recovery/06-COLLAPSE-PLAN.md} §5 item 6).
 *
 * <p>Its own class rather than a method on {@code PurgeJobs}, following the precedent collapse
 * step 6 set with {@code finance.trove.transactions.TransferPairingJob}: {@code ScheduledJobsTest}
 * scans every compiled class for {@code @Scheduled} and asserts lock names are unique across the
 * whole application, so a job belongs with the code it drives rather than in a shared sweeper.
 *
 * <p>The lock name and the two durations are fixed by §5 item 6 and are the source's.
 * {@code lockAtMostFor = PT29S} is one second under the 30-second period: if a pass dies without
 * releasing, the next one may start rather than the queue stopping for ever.
 * {@code lockAtLeastFor = PT5S} keeps a fast empty pass from being immediately re-run by a second
 * instance whose clock is a little ahead.
 *
 * <p>There is no {@code cron} here, so {@code ScheduledJobsTest}'s {@code zone="UTC"} rule does
 * not apply: a fixed delay has no calendar to be wrong about.
 *
 * <h2>Why the trigger is off under the {@code test} profile</h2>
 * This is the first {@code fixedDelay} job in the application that does OUTBOUND NETWORK I/O, and
 * the integration suite boots the whole application ({@code BaseIT}) with the one
 * {@code @EnableScheduling} live and no {@code application-test.yml} to switch it off. Left
 * triggering, it fires at context start and every thirty seconds for the whole run, and it does
 * two unacceptable things: it POSTs to whatever addresses the webhook tests have stored, so
 * {@code mvn verify} makes unsolicited third-party requests and spends a ten-second connect
 * timeout per row on a host with no egress; and it CLAIMS the very rows
 * {@code WebhookDeliveryIT} is asserting about, so a pass landing between {@code scheduleDelivery}
 * and the assertion turns a correct build red at random.
 *
 * <p>The trigger is the only thing switched off. {@link #retryDueDeliveries()} is a two-line
 * delegation covered by {@code WebhookRetryJobTest}, and everything it delegates to —
 * {@code claimDue}, {@code attempt}, {@code persistOutcome}, the egress re-check — is driven
 * explicitly and end to end by {@code WebhookDeliveryIT} against the real beans. The alternative,
 * a period property the suite could set to something enormous, needs a line in {@code BaseIT} or
 * {@code application-test.yml}, and this step may edit neither (steps 15 and 17 are in their own
 * worktrees); that exact diff is offered in the step's report instead.
 */
@Component
@Profile("!test")
public class WebhookRetryJob {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookRetryJob.class);

    private final WebhookDeliveryService deliveries;

    public WebhookRetryJob(WebhookDeliveryService deliveries) {
        this.deliveries = deliveries;
    }

    /** One pass: claim what is due, call out, write the outcomes. */
    @Scheduled(fixedDelay = 30_000L)
    @SchedulerLock(name = "webhook-retry", lockAtMostFor = "PT29S", lockAtLeastFor = "PT5S")
    public void retryDueDeliveries() {
        int attempted = deliveries.deliverDue();
        if (attempted > 0) {
            LOG.debug("webhook-retry: attempted {} deliveries", attempted);
        }
    }
}
