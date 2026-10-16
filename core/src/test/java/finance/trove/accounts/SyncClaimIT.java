package finance.trove.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.LedgerSupport;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The sync claim is a lease: a claim left behind by a crashed JVM must release itself (B1-03),
 * while a live recent claim must still refuse a second taker.
 */
class SyncClaimIT extends LedgerSupport {

    @Autowired
    private ConnectedAccountRepository accounts;

    @Autowired
    private TransactionTemplate tx;

    @Test
    @DisplayName("A SYNCING claim older than the lease can be taken again; a fresh one cannot")
    void staleClaimExpires() {
        Session admin = loginBootstrapAdmin();
        UUID id = UUID.fromString(createSyncableAccount(admin, "Lease " + UUID.randomUUID()));

        jdbcTemplate.update("UPDATE connected_account SET sync_status = 'SYNCING', "
            + "updated_at = now() WHERE id = ?", id);
        assertThat(claim(id, admin)).as("a fresh claim is still held").isZero();

        jdbcTemplate.update("UPDATE connected_account SET updated_at = now() - interval '2 hours' "
            + "WHERE id = ?", id);
        assertThat(claim(id, admin)).as("a claim from a crashed run expires").isEqualTo(1);
    }

    private int claim(UUID id, Session admin) {
        Integer count = tx.execute(status -> accounts.claimForSync(id, admin.householdId()));
        return count == null ? -1 : count;
    }
}
