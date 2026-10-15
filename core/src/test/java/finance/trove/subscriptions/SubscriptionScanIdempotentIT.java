package finance.trove.subscriptions;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import finance.trove.subscriptions.scheduler.SubscriptionScanJob;
import tools.jackson.databind.JsonNode;

/**
 * The upsert, from the outside: a second scan over unchanged history moves no column, and two
 * scans of one household running at once produce one row.
 *
 * <p>The source did {@code findByUserIdAndMerchantName(...).orElseGet(...)} then {@code save(...)}
 * ({@code SubscriptionDetectionService.java:124-138}). That check-then-act was safe only because
 * one sequential scheduler was the sole caller. This step adds a manual trigger and an ingestion
 * listener, so the duplicate-insert race (inventory SD-5) becomes reachable — which is why V29
 * carries {@code UNIQUE (household_id, merchant_name)} and the write is
 * {@code INSERT ... ON CONFLICT DO UPDATE}.
 */
class SubscriptionScanIdempotentIT extends SubscriptionSupport {

    @Autowired
    private SubscriptionScanJob job;

    private Session admin;
    private String accountId;

    @BeforeEach
    void signIn() {
        admin = loginBootstrapAdmin();
        accountId = createManualAccount(admin, "Idempotence " + UUID.randomUUID());
    }

    @Test
    @DisplayName("Two scans over unchanged history leave every column exactly as it was")
    void aSecondScanIsANoOp() {
        String merchant = uniqueMerchant("STABLE");
        seedMonthlyCharges(admin, accountId, merchant, "16.40", 3, 1);

        JsonNode first = subscriptionFor(scan(admin), merchant);
        JsonNode second = subscriptionFor(scan(admin), merchant);
        JsonNode third = subscriptionFor(scan(admin), merchant);

        assertThat(storedSubscriptionCount(admin.householdId(), merchant)).isEqualTo(1);
        for (String field : List.of("id", "normalizedAmount", "previousAmount", "currency",
            "billingCycle", "nextChargeDate", "signal", "priceChanged", "firstSeenDate",
            "lastSeenDate", "cancelled", "createdBy", "createdAt")) {
            assertThat(second.path(field).toString())
                .as("a repeat scan changed %s", field)
                .isEqualTo(first.path(field).toString());
            assertThat(third.path(field).toString())
                .as("and a third scan changed %s", field)
                .isEqualTo(first.path(field).toString());
        }
    }

    @Test
    @DisplayName("The nightly job and the manual trigger produce the same single row")
    void theJobAndTheManualTriggerAgree() {
        String merchant = uniqueMerchant("ONE OWNER");
        seedMonthlyCharges(admin, accountId, merchant, "4.99", 3, 1);

        job.runOnce();
        JsonNode afterJob = subscriptionFor(listSubscriptions(admin), merchant);
        JsonNode afterManual = subscriptionFor(scan(admin), merchant);

        assertThat(afterManual.path("id").asString())
            .as("three triggers, one method: a household cannot be scanned two subtly different "
                + "ways")
            .isEqualTo(afterJob.path("id").asString());
        assertThat(afterManual.path("normalizedAmount").asString())
            .isEqualTo(afterJob.path("normalizedAmount").asString());
        assertThat(storedSubscriptionCount(admin.householdId(), merchant)).isEqualTo(1);
    }

    @Test
    @DisplayName("A manual trigger running beside the nightly job does not duplicate the row")
    void aConcurrentScanBesideTheJobDoesNotDuplicate() throws Exception {
        String merchant = uniqueMerchant("RACED");
        seedMonthlyCharges(admin, accountId, merchant, "6.66", 3, 1);

        // A real two-thread race, the way InviteIT's redemption and AccessControlIT's last-admin
        // guard are written: both workers wait on one latch so that neither has finished before
        // the other starts. Without V29's unique constraint and the ON CONFLICT that targets it,
        // both would find no row and both would insert.
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> viaJob = pool.submit(() -> {
                start.await();
                return job.runOnce();
            });
            Future<Integer> viaHttp = pool.submit(() -> {
                start.await();
                return scan(admin).size();
            });

            start.countDown();
            assertThat(viaJob.get(120, TimeUnit.SECONDS)).isNotNegative();
            assertThat(viaHttp.get(120, TimeUnit.SECONDS)).isNotNegative();
        } finally {
            pool.shutdownNow();
        }

        assertThat(storedSubscriptionCount(admin.householdId(), merchant))
            .as("SD-5: the constraint is what makes the second writer update instead of inserting")
            .isEqualTo(1);
        assertThat(subscriptionFor(listSubscriptions(admin), merchant)
            .path("normalizedAmount").asString())
            .isEqualTo("6.6600");
    }

    @Test
    @DisplayName("Two manual triggers at once still produce one row")
    void twoConcurrentManualTriggersDoNotDuplicate() throws Exception {
        String merchant = uniqueMerchant("RACED TWICE");
        seedMonthlyCharges(admin, accountId, merchant, "2.50", 3, 1);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> both = List.of(
                pool.submit(() -> {
                    start.await();
                    return scan(admin).size();
                }),
                pool.submit(() -> {
                    start.await();
                    return scan(admin).size();
                }));
            start.countDown();
            for (Future<Integer> attempt : both) {
                assertThat(attempt.get(120, TimeUnit.SECONDS)).isNotNegative();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(storedSubscriptionCount(admin.householdId(), merchant)).isEqualTo(1);
    }
}
