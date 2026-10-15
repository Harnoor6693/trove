package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B1-21: a background write (transfer pairing, category write-back) holds a copy of the row read
 * before a member's PATCH commits. With full-row updates it wrote the stale columns back and
 * erased the member's edit. The latches make the interleaving deterministic.
 */
class TransactionLostUpdateIT extends LedgerSupport {

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private TransactionTemplate tx;

    @Test
    @DisplayName("A background write to transferGroupId does not erase a concurrent notes edit")
    void backgroundWriteKeepsAConcurrentPatch() throws Exception {
        Session admin = loginBootstrapAdmin();
        String account = createManualAccount(admin, "Race " + UUID.randomUUID());
        String id = createManualTransaction(admin, account, "10.00", "2026-09-04", "DEBIT",
            "RACE " + UUID.randomUUID()).path("id").asString();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch patched = new CountDownLatch(1);

        CompletableFuture<Void> background = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            Transaction row = transactions.findByIdAndHouseholdId(UUID.fromString(id),
                admin.householdId()).orElseThrow();
            read.countDown();
            await(patched);
            row.setTransferGroupId(UUID.randomUUID());
        }));
        assertThat(read.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(patchJson(appUrl("/v1/transactions/" + id),
            Json.object("notes", "member edit"),
            "Authorization", "Bearer " + admin.accessToken()).statusCode()).isEqualTo(200);
        patched.countDown();
        background.get(30, TimeUnit.SECONDS);

        assertThat(getTransaction(admin, id).path("notes").asString())
            .as("the member's edit survives a background write of a different column")
            .isEqualTo("member edit");
        assertThat(getTransaction(admin, id).path("transferGroupId").isNull()).isFalse();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
