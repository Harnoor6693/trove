package finance.trove.ingestion;

import finance.trove.common.repository.UserScopedRepository;
import java.util.List;
import java.util.UUID;

/**
 * Repository over {@link SyncLog}. Scoped like every other ledger repository, so a sync history
 * is readable by the household that owns the account and by nobody else.
 */
public interface SyncLogRepository extends UserScopedRepository<SyncLog, UUID> {

    /**
     * The account's most recent runs, newest first.
     *
     * <p>Capped at fifty in the method name rather than paged: {@code sync_log} grows by one row
     * per sync for ever, and a history screen that asked for all of them would get slower every
     * week while showing the member nothing they would ever scroll to.
     */
    List<SyncLog> findTop50ByHouseholdIdAndAccountIdOrderByStartedAtDesc(UUID householdId,
                                                                        UUID accountId);
}
