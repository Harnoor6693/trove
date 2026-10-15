package finance.trove.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.LedgerSupport;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.SourceKind;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B1-15: two first-time upserts of the same provider account must end with one row and no error.
 * A latch holds the first transaction open after its insert, so the second one genuinely collides
 * on the unique key instead of racing by luck.
 */
class AccountUpsertRaceIT extends LedgerSupport {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransactionTemplate tx;

    @Test
    @DisplayName("A concurrent first upsert of the same provider account reuses the winner's row")
    void concurrentUpsertsShareOneRow() throws Exception {
        Session admin = loginBootstrapAdmin();
        String external = "race-" + UUID.randomUUID();
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<UUID> winner = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            UUID id = upsert(admin, external);
            inserted.countDown();
            await(release);
            return id;
        }));
        assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<UUID> loser = CompletableFuture.supplyAsync(() -> upsert(admin, external));
        Thread.sleep(500); // let the loser reach the unique key and block on the winner's row
        release.countDown();

        UUID winnerId = winner.get(30, TimeUnit.SECONDS);
        UUID loserId = loser.get(30, TimeUnit.SECONDS);

        assertThat(loserId).isEqualTo(winnerId);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM connected_account "
            + "WHERE household_id = ? AND external_account_id = ?", Integer.class,
            admin.householdId(), external)).isEqualTo(1);
    }

    private UUID upsert(Session admin, String external) {
        return accountService.upsertProviderAccount(admin.householdId(), admin.userId(),
            SourceKind.SIMPLEFIN, external, "Race", null, AccountType.OTHER, CurrencyCode.CAD,
            null).getId();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
