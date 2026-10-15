package finance.trove.ingestion;

import finance.trove.common.plugin.SourceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The record of one sync run ({@code docs/recovery/06-COLLAPSE-PLAN.md} §3 V20).
 *
 * <p>Append-only: one row is written {@code RUNNING} when a run claims an account, and updated
 * exactly once when it ends. Each of those two writes happens in its own short transaction —
 * separate from the batch that persists transactions — precisely so that a run which accepted
 * some rows and refused others can still record {@code PARTIAL_FAILURE}. Inside one big
 * transaction it could not: the per-row failure would have marked it rollback-only and the final
 * save would throw instead of recording anything (§6).
 *
 * <p>Scoped by {@code household_id} like every other ledger table, so any member of the household
 * can see why a connection stopped working, not only whoever set it up.
 */
@Entity
@Table(name = "sync_log")
public class SyncLog {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "household_id", nullable = false, updatable = false)
    private UUID householdId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", length = 20, updatable = false)
    private SourceKind sourceKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SyncRunStatus status = SyncRunStatus.RUNNING;

    @Column(name = "transactions_fetched", nullable = false)
    private int transactionsFetched;

    @Column(name = "transactions_new", nullable = false)
    private int transactionsNew;

    @Column(name = "transactions_updated", nullable = false)
    private int transactionsUpdated;

    @Column(name = "transactions_skipped", nullable = false)
    private int transactionsSkipped;

    @Column(name = "transactions_failed", nullable = false)
    private int transactionsFailed;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SyncLog() {
        // JPA
    }

    public SyncLog(UUID householdId, UUID accountId, UUID userId, SourceKind sourceKind,
                   Instant startedAt) {
        this.householdId = householdId;
        this.accountId = accountId;
        this.userId = userId;
        this.sourceKind = sourceKind;
        this.startedAt = startedAt;
    }

    /**
     * Closes the run.
     *
     * @param errorMessage why it failed, or which rows were refused; {@code null} on success
     */
    public void complete(SyncRunStatus status, int fetched, int created, int updated, int skipped,
                         int failed, String errorMessage, Instant completedAt) {
        this.status = status;
        this.transactionsFetched = fetched;
        this.transactionsNew = created;
        this.transactionsUpdated = updated;
        this.transactionsSkipped = skipped;
        this.transactionsFailed = failed;
        this.errorMessage = errorMessage;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getHouseholdId() {
        return householdId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public UUID getUserId() {
        return userId;
    }

    public SourceKind getSourceKind() {
        return sourceKind;
    }

    public SyncRunStatus getStatus() {
        return status;
    }

    public int getTransactionsFetched() {
        return transactionsFetched;
    }

    public int getTransactionsNew() {
        return transactionsNew;
    }

    public int getTransactionsUpdated() {
        return transactionsUpdated;
    }

    public int getTransactionsSkipped() {
        return transactionsSkipped;
    }

    public int getTransactionsFailed() {
        return transactionsFailed;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
