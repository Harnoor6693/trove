package finance.trove.ingestion;

import finance.trove.accounts.AccountService;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.accounts.SyncStatus;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.events.SyncCompletedEvent;
import finance.trove.common.exception.ConflictException;
import finance.trove.common.plugin.BankSource;
import finance.trove.common.plugin.BankSourceConnectionException;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.time.UserClock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs one synchronisation for one account ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.2,
 * §6 M0; {@code docs/recovery/inventory/ingestion-service.md} D4).
 *
 * <h2>This method is deliberately not {@code @Transactional}</h2>
 * The pre-collapse version was one long method that fetched from the network, wrote rows, and
 * saved a log, catching everything at the end. Making that transactional — which is the obvious
 * fix for its half-written states — would break the one outcome it most needs to record: a
 * per-row failure marks the transaction rollback-only, so the {@code PARTIAL_FAILURE} save at the
 * end throws {@code UnexpectedRollbackException} and no sync can ever complete partially. So the
 * work is split into pieces that each own their own transaction:
 * <ol>
 *   <li>the <strong>claim</strong> ({@link SyncStateWriter#claim}), one conditional UPDATE,
 *       committed at once;</li>
 *   <li>the <strong>opening log row</strong>, its own transaction, so a run that dies is still
 *       visible;</li>
 *   <li>the <strong>fetch</strong>, outside any transaction — it blocks on somebody else's
 *       network and must not hold a database connection while it does;</li>
 *   <li>the <strong>batch</strong> ({@link IngestionBatchWriter}), one short transaction over the
 *       accepted rows;</li>
 *   <li>the <strong>closing log row</strong> and the <strong>release</strong>, each their own.</li>
 * </ol>
 *
 * <h2>Two overlapping syncs cannot both proceed</h2>
 * The claim is taken before anything else and released on every path out, exceptions included. A
 * caller that loses it is answered 409 rather than being allowed to fetch the same window and
 * collide with the winner on the unique dedup index halfway through.
 *
 * <h2>A failure is an outcome, not an exception</h2>
 * A source that cannot be reached produces a {@code FAILED} log with the reason and a 200: the
 * request succeeded in recording what happened, and a member needs to be told that their
 * connection's credentials have expired rather than shown a generic error. The exception is
 * turned into {@link SyncCompletedEvent} with {@code Outcome.FAILURE} — the event vocabulary
 * spells it differently from the column, and {@link SyncRunStatus#toOutcome()} is the single place
 * that mapping is made.
 */
@Service
public class SyncOrchestrator {

    /** How far back a first sync reaches when the account has never been synced. */
    public static final int FIRST_SYNC_LOOKBACK_DAYS = 90;

    /**
     * How far before {@code last_synced_at} a subsequent sync starts.
     *
     * <p>Not zero. A transaction can be reported by an institution days after its own date, so a
     * window that began exactly where the last one ended would miss it for ever. Re-fetching a few
     * days costs nothing — the exact-match key recognises every row it has already stored — and it
     * is far cheaper than a transaction the member never sees.
     */
    public static final int OVERLAP_DAYS = 3;

    private static final Logger LOG = LoggerFactory.getLogger(SyncOrchestrator.class);

    private static final TypeReference<Map<String, String>> CONFIG_SHAPE =
        new TypeReference<Map<String, String>>() { };

    private final AccountService accounts;
    private final SyncLogRepository logs;
    private final SourceRegistry sources;
    private final SyncStateWriter state;
    private final IngestionBatchWriter batchWriter;
    private final FieldEncryptionService encryption;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher events;
    private final UserClock clock;

    public SyncOrchestrator(AccountService accounts, SyncLogRepository logs,
                            SourceRegistry sources, SyncStateWriter state,
                            IngestionBatchWriter batchWriter, FieldEncryptionService encryption,
                            ObjectMapper objectMapper, ApplicationEventPublisher events,
                            UserClock clock) {
        this.accounts = accounts;
        this.logs = logs;
        this.sources = sources;
        this.state = state;
        this.batchWriter = batchWriter;
        this.encryption = encryption;
        this.objectMapper = objectMapper;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Synchronises one account and returns the run's log.
     *
     * @throws finance.trove.common.exception.ResourceNotFoundException if the id is not this
     *         household's — 404, never 403
     * @throws ConflictException if another run holds the claim, or if no source is registered for
     *         this account's kind
     */
    public SyncLog sync(UUID householdId, UUID userId, UUID accountId) {
        return sync(householdId, userId, accountId, null, null);
    }

    /**
     * The same run, with the configuration and the batch identity a FILE IMPORT supplies
     * ({@code docs/recovery/07-READINESS-GAPS.md} P2-12).
     *
     * <p><strong>This is the one overload, and the three-argument method above delegates to it.</strong>
     * Commit reuses this pipeline rather than a parallel one, which is what keeps the claim, the
     * fetch-outside-any-transaction rule, the single short batch transaction, the {@code sync_log}
     * row and the {@code afterCommit} event identical for an import and for a bank sync. A second
     * implementation of any of those would be a second place for them to drift.
     *
     * <p>Two parameters, and both are null on the bank-sync path, so its behaviour is unchanged:
     * <ul>
     *   <li>{@code config} — a file import has no stored credential at all (an OFX or CSV
     *       account's {@code config_encrypted} is null, which is why V18 made the column nullable),
     *       so the BATCH's configuration is passed instead: its storage key, its column mapping and
     *       its account id. {@code null} means "the account's own, decrypted here".</li>
     *   <li>{@code importBatchId} — stamped onto every row this run inserts, through
     *       {@link IngestionBatchWriter#persist}. It is what {@code DELETE /v1/imports/{id}} later
     *       finds the batch's own rows by; without it an undo would have nothing to undo.</li>
     * </ul>
     */
    public SyncLog sync(UUID householdId, UUID userId, UUID accountId,
                        Map<String, String> config, UUID importBatchId) {
        ConnectedAccount account = accounts.get(householdId, accountId);
        BankSource source = sources.find(account.getSourceKind())
            .orElseThrow(() -> new ConflictException("No source is registered for "
                + account.getSourceKind() + " accounts on this install, so there is nothing to "
                + "sync from. Add transactions by hand, or connect a source."));

        if (!state.claim(householdId, accountId)) {
            throw new ConflictException("This account is already being synchronised. Wait for the "
                + "run in progress to finish.");
        }

        SyncLog opened;
        try {
            opened = state.start(account, userId, clock.now());
        } catch (RuntimeException e) {
            // The claim is already taken; failing to open the log must not leave it taken for ever.
            state.release(householdId, accountId, SyncStatus.ERROR,
                "The sync could not be started.", false);
            throw e;
        }

        SyncRunStatus status = SyncRunStatus.FAILED;
        String failureReason = null;
        int fetched = 0;
        IngestionBatch batch = new IngestionBatch(List.of(), List.of(), 0, 0, null);

        try {
            List<RawTransaction> rows = source.fetch(account.getExternalAccountId(),
                windowStart(account, userId), clock.today(userId),
                withMemberZone(config != null ? config : configOf(account), userId));
            fetched = rows.size();

            batch = batchWriter.persist(account, userId, rows, importBatchId);
            status = batch.hasFailures() ? SyncRunStatus.PARTIAL_FAILURE : SyncRunStatus.SUCCESS;
            failureReason = batch.failureReason();
        } catch (BankSourceConnectionException e) {
            LOG.warn("Sync of account {} could not reach its source: {}", accountId,
                e.getMessage());
            failureReason = e.getMessage();
        } catch (RuntimeException e) {
            // The reason is stored and shown to a member, so it names the failure class rather
            // than echoing an exception message that may quote internals.
            LOG.error("Sync of account {} failed unexpectedly", accountId, e);
            failureReason = "The sync failed unexpectedly (" + e.getClass().getSimpleName()
                + "). The server log holds the detail.";
        } finally {
            // `stored` is the ONLY thing that may move last_synced_at, and it is derived from
            // `status` rather than tracked in a second flag on purpose. `status` is assigned
            // exactly once, immediately after batchWriter.persist(...) has RETURNED — that is,
            // after its transaction has committed — so `status != FAILED` means "this run
            // durably kept what it read" and nothing else can drift from it.
            //
            // Tracking "did we reach the source?" separately was a real defect: persist() is
            // transactional, so any RuntimeException it throws (a constraint violation, a lost
            // connection, a bug in categorisation) rolls the whole batch back and writes
            // NOTHING, yet the run had plainly reached its source. Stamping last_synced_at on
            // that path moved the next run's window forward past rows that were fetched and
            // never stored, and — because the exact-match key never sees them again — they were
            // silently and permanently absent from the household's ledger.
            boolean stored = status != SyncRunStatus.FAILED;
            state.release(householdId, accountId, stored ? SyncStatus.OK : SyncStatus.ERROR,
                stored ? null : failureReason, stored);
        }

        SyncLog finished = state.finish(householdId, opened.getId(), status, fetched,
            batch.created(), batch.updated(), batch.skipped(), batch.failed(), failureReason);

        events.publishEvent(new SyncCompletedEvent(householdId, userId, accountId,
            finished.getId(), status.toOutcome(), batch.created(), batch.updated(),
            failureReason));
        return finished;
    }

    /** The account's recent runs. The account is resolved first, so a foreign id is 404. */
    public List<SyncLog> recentLogs(UUID householdId, UUID accountId) {
        accounts.get(householdId, accountId);
        return logs.findTop50ByHouseholdIdAndAccountIdOrderByStartedAtDesc(householdId, accountId);
    }

    /**
     * Where this run's window begins, in the triggering member's own timezone (gap P2-15).
     *
     * <p>The pre-collapse code called {@code LocalDate.now()} with no zone here, so on an install
     * whose container is UTC — which is every install, by policy — a member west of Greenwich
     * asked their bank for a window that ended before their own evening.
     */
    private LocalDate windowStart(ConnectedAccount account, UUID userId) {
        if (account.getLastSyncedAt() == null) {
            return clock.today(userId).minusDays(FIRST_SYNC_LOOKBACK_DAYS);
        }
        return clock.dateOf(userId, account.getLastSyncedAt()).minusDays(OVERLAP_DAYS);
    }

    /**
     * {@code config} plus the member's zone under {@link BankSource#MEMBER_ZONE}: the window is
     * the member's days, and so are the dates of the rows in it (B1-22).
     */
    private Map<String, String> withMemberZone(Map<String, String> config, UUID userId) {
        Map<String, String> withZone = new HashMap<>(config);
        withZone.put(BankSource.MEMBER_ZONE, clock.zoneOf(userId).getId());
        return withZone;
    }

    /**
     * The account's decrypted source configuration, or empty for a source that needs none.
     *
     * <p>Decryption happens here and nowhere else, immediately before the source is called, and
     * the plaintext does not outlive this method's caller. A MANUAL, CSV or OFX account has no
     * blob at all, which is why {@code config_encrypted} is nullable.
     */
    private Map<String, String> configOf(ConnectedAccount account) {
        String encrypted = account.getConfigEncrypted();
        if (encrypted == null || encrypted.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(encryption.decrypt(encrypted), CONFIG_SHAPE);
    }
}
