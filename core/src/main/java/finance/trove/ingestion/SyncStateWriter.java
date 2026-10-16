package finance.trove.ingestion;

import finance.trove.accounts.ConnectedAccount;
import finance.trove.accounts.ConnectedAccountRepository;
import finance.trove.accounts.SyncStatus;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.time.Instants;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every short transaction a sync run needs, on a bean of its own
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 M0).
 *
 * <p><strong>Why these are not methods on {@code SyncOrchestrator}.</strong> That class is
 * deliberately not transactional, and Spring's {@code @Transactional} is applied by a proxy: a
 * call to {@code this.claim(...)} from inside the same object would run with no transaction at
 * all, silently, and the claim that prevents two concurrent syncs would not be a claim. Putting
 * them on an injected bean is what makes the proxy real. It is the same self-invocation trap that
 * broke the pre-collapse categorisation write-back (§2.3 D4), applied here before it can happen.
 *
 * <p>Every method is {@code REQUIRES_NEW}. The claim must be visible to other threads the instant
 * it is taken, the {@code RUNNING} log must survive a run that later fails, and the final log must
 * be written even when a caller's own transaction — if it ever had one — is already doomed.
 */
@Component
public class SyncStateWriter {

    private final ConnectedAccountRepository accounts;
    private final SyncLogRepository logs;

    public SyncStateWriter(ConnectedAccountRepository accounts, SyncLogRepository logs) {
        this.accounts = accounts;
        this.logs = logs;
    }

    /**
     * Takes the {@code SYNCING} claim.
     *
     * <p>One conditional statement, committed immediately. Two overlapping triggers therefore
     * cannot both proceed: whichever {@code UPDATE} lands second matches no row, because the first
     * has already changed the value it was testing for. An in-memory "is it syncing?" check could
     * not give that guarantee — both callers would read {@code OK} and both would go on to fetch
     * the same window and collide on the unique dedup index halfway through.
     *
     * @return {@code true} when this caller took the claim
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(UUID householdId, UUID accountId) {
        return accounts.claimForSync(accountId, householdId) == 1;
    }

    /**
     * Releases the claim on <strong>every</strong> path out of a run, exceptions included.
     *
     * <p>A claim that is not released leaves the account answering 409 until the one-hour lease
     * expires, and there is no screen anywhere that would let a member clear it sooner.
     *
     * @param durablyStored whether the run actually <strong>stored</strong> what it read, which
     *                      is the only thing that may move {@code last_synced_at}. Reaching the
     *                      source is NOT enough: the batch write is a transaction of its own, so
     *                      a run whose fetch succeeded and whose persist then rolled back kept
     *                      nothing at all. Stamping on that path would move the next run's window
     *                      past rows that were fetched and never stored, and nothing would ever
     *                      ask for them again
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID householdId, UUID accountId, SyncStatus status, String errorMessage,
                        boolean durablyStored) {
        accounts.releaseClaim(accountId, householdId, status.name(), errorMessage, durablyStored);
    }

    /** Opens the run's log row, committed at once so a run that dies is still visible. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncLog start(ConnectedAccount account, UUID userId, Instant startedAt) {
        SourceKind kind = account.getSourceKind();
        return logs.save(new SyncLog(account.getHouseholdId(), account.getId(), userId, kind,
            startedAt));
    }

    /**
     * Closes the run's log row.
     *
     * <p>Its own transaction, and that is the fix §6 names: with the whole sync inside one
     * transaction, a per-row failure would mark it rollback-only and this save would throw
     * {@code UnexpectedRollbackException}, so a {@code PARTIAL_FAILURE} could never be recorded at
     * all.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SyncLog finish(UUID householdId, UUID logId, SyncRunStatus status, int fetched,
                          int created, int updated, int skipped, int failed, String errorMessage) {
        SyncLog log = logs.findByIdAndHouseholdId(logId, householdId)
            .orElseThrow(() -> new IllegalStateException(
                "The sync_log row this run opened is gone: " + logId));
        log.complete(status, fetched, created, updated, skipped, failed, errorMessage,
            Instants.now());
        return logs.save(log);
    }
}
