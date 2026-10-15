package finance.trove.ingestion.dto;

import finance.trove.common.plugin.SourceKind;
import finance.trove.ingestion.SyncLog;
import finance.trove.ingestion.SyncRunStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One sync run as {@code POST /v1/accounts/{id}/sync} and
 * {@code GET /v1/accounts/{id}/sync-logs} return it.
 *
 * @param errorMessage why the run failed, or which rows it refused. It never quotes a transaction:
 *                     a description is a household's spending history and this string is shown in
 *                     a UI and stored for ever
 */
public record SyncLogResponse(UUID id, UUID householdId, UUID accountId, SourceKind sourceKind,
                              SyncRunStatus status, int transactionsFetched, int transactionsNew,
                              int transactionsUpdated, int transactionsSkipped,
                              int transactionsFailed, String errorMessage, Instant startedAt,
                              Instant completedAt) {

    public static SyncLogResponse of(SyncLog log) {
        return new SyncLogResponse(log.getId(), log.getHouseholdId(), log.getAccountId(),
            log.getSourceKind(), log.getStatus(), log.getTransactionsFetched(),
            log.getTransactionsNew(), log.getTransactionsUpdated(), log.getTransactionsSkipped(),
            log.getTransactionsFailed(), log.getErrorMessage(), log.getStartedAt(),
            log.getCompletedAt());
    }
}
