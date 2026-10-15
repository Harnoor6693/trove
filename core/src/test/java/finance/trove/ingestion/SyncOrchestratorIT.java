package finance.trove.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.app.support.Json;
import finance.trove.app.support.LedgerSupport;
import finance.trove.common.plugin.BankSource;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.TransactionType;
import finance.trove.common.time.UserClock;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * The sync pipeline end to end ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 M0;
 * {@code docs/recovery/inventory/ingestion-service.md} D4).
 *
 * <p>Five things are proved here that no unit test can:
 * <ul>
 *   <li>the {@code SYNCING} claim really is a claim, under <strong>two real threads</strong>, with
 *       the loser told 409 — written the way {@code InviteIT} and
 *       {@code AccessControlIT#theLastAdminGuardHoldsUnderConcurrency} are, because a flake here
 *       shows up as a 401-for-409 and never as a false pass;</li>
 *   <li>a run that accepts some rows and refuses others really records {@code PARTIAL_FAILURE} —
 *       the outcome that was impossible while the whole sync was one transaction, because the
 *       per-row failure marked it rollback-only and the final save threw
 *       {@code UnexpectedRollbackException};</li>
 *   <li>a second sync of the same rows stores nothing new, which is the O7/M0 regression: with the
 *       plugin name in the dedup column, an account could hold exactly one transaction, for
 *       ever;</li>
 *   <li>the claim is released on every path, failures included, so a failed sync does not leave an
 *       account nobody can ever sync again;</li>
 *   <li>a run whose fetch succeeded and whose batch write then rolled back leaves
 *       {@code last_synced_at} alone — the batch is a transaction of its own, so reaching the
 *       source is not the same as keeping what it gave, and stamping on that path would move the
 *       next run's window past rows nothing ever stored.</li>
 * </ul>
 */
class SyncOrchestratorIT extends LedgerSupport {

    @Autowired
    private TestBankSource source;

    @Autowired
    private UserClock clock;

    private Session admin;
    private String accountId;

    @BeforeEach
    void setUpAnAccount() {
        source.reset();
        admin = loginBootstrapAdmin();
        accountId = createSyncableAccount(admin, "Bank " + UUID.randomUUID());
    }

    @AfterEach
    void releaseTheSource() {
        source.releaseFetch();
        source.reset();
    }

    @Test
    @DisplayName("A sync stores what the source offered and reports it in the log")
    void aSyncStoresWhatTheSourceOffered() {
        source.returning(List.of(
            row("bank-1", "10.00", LocalDate.of(2026, 9, 1)),
            row("bank-2", "20.00", LocalDate.of(2026, 9, 2)),
            row("bank-3", "30.00", LocalDate.of(2026, 9, 3))));

        JsonNode log = Json.data(sync().body());

        assertThat(log.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(log.path("transactionsFetched").asInt()).isEqualTo(3);
        assertThat(log.path("transactionsNew").asInt()).isEqualTo(3);
        assertThat(log.path("transactionsSkipped").asInt()).isZero();
        assertThat(log.path("transactionsFailed").asInt()).isZero();
        assertThat(log.path("errorMessage").isNull()).isTrue();
        assertThat(log.path("completedAt").isNull()).isFalse();
        assertThat(transactionIds(admin, "accountId=" + accountId)).hasSize(3);
    }

    @Test
    @DisplayName("O7/M0: a second sync of the same rows stores nothing new — the pre-collapse "
        + "dedup column held the plugin's NAME, so an account could hold one transaction, ever")
    void aRepeatedSyncIsIdempotent() {
        source.returning(List.of(
            row("bank-1", "10.00", LocalDate.of(2026, 9, 1)),
            row("bank-2", "20.00", LocalDate.of(2026, 9, 2)),
            row("bank-3", "30.00", LocalDate.of(2026, 9, 3))));

        sync();
        JsonNode second = Json.data(sync().body());

        assertThat(second.path("transactionsNew").asInt()).isZero();
        assertThat(second.path("transactionsSkipped").asInt()).isEqualTo(3);
        assertThat(transactionIds(admin, "accountId=" + accountId))
            .as("three rows after the first sync, three after the second")
            .hasSize(3);
    }

    @Test
    @DisplayName("Two same-day, same-amount PENDING rows both persist — they are two purchases")
    void twoIdenticalPendingRowsBothPersist() {
        LocalDate day = LocalDate.of(2026, 9, 4);
        source.returning(List.of(
            pending("bank-a", "12.34", day),
            pending("bank-b", "12.34", day)));

        JsonNode log = Json.data(sync().body());

        assertThat(log.path("transactionsNew").asInt())
            .as("the old fuzzy pass matched any pending row in the window regardless of the "
                + "candidate's own id, so the member's second coffee silently never existed")
            .isEqualTo(2);
        assertThat(transactionIds(admin, "accountId=" + accountId)).hasSize(2);
    }

    @Test
    @DisplayName("A pending row that posts is promoted in place, not stored twice")
    void aPendingRowThatPostsIsPromoted() {
        LocalDate day = LocalDate.of(2026, 9, 4);
        source.returning(List.of(pending("bank-1", "12.34", day)));
        sync();

        source.returning(List.of(row("bank-1", "12.34", day)));
        JsonNode log = Json.data(sync().body());

        assertThat(log.path("transactionsUpdated").asInt()).isEqualTo(1);
        assertThat(log.path("transactionsNew").asInt()).isZero();
        List<JsonNode> rows = listTransactions(admin, "accountId=" + accountId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("pending").asBoolean()).isFalse();
        assertThat(rows.get(0).path("postedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("B1-07: a pre-authorisation that posts with a tip takes the posted amount")
    void aPromotedRowTakesThePostedAmount() {
        LocalDate day = LocalDate.of(2026, 9, 4);
        source.returning(List.of(pending("bank-tip", "40.00", day)));
        sync();

        source.returning(List.of(row("bank-tip", "48.00", day)));
        JsonNode log = Json.data(sync().body());

        assertThat(log.path("transactionsUpdated").asInt()).isEqualTo(1);
        List<JsonNode> rows = listTransactions(admin, "accountId=" + accountId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("pending").asBoolean()).isFalse();
        assertThat(new BigDecimal(rows.get(0).path("amount").asString()))
            .as("the bank charged 48.00; the ledger kept the 40.00 pre-authorisation for ever")
            .isEqualByComparingTo("48.00");
    }

    @Test
    @DisplayName("B1-22: the source is told the member's zone, so it dates rows where they live")
    void theSourceIsToldTheMembersZone() {
        source.returning(List.of());

        sync();

        assertThat(source.lastConfig())
            .as("SimpleFIN stamps rows in epoch seconds; read in UTC, a purchase at 20:00 in "
                + "Toronto on the 31st landed on the 1st, and so in the next month")
            .containsEntry(BankSource.MEMBER_ZONE, clock.zoneOf(admin.userId()).getId());
    }

    @Test
    @DisplayName("A run that refuses some rows records PARTIAL_FAILURE and keeps the good ones")
    void aPartialFailureIsRecorded() {
        source.returning(List.of(
            row("bank-good-1", "10.00", LocalDate.of(2026, 9, 1)),
            // No date. Refused BEFORE anything is written, which is the whole reason a partial
            // failure can be recorded at all: inside one transaction the rejection would have
            // marked it rollback-only and the log save below would have thrown instead.
            new RawTransaction("bank-bad", "ext", new BigDecimal("5.00"), "CAD", "no date", null,
                null, null, TransactionType.DEBIT, false, null),
            row("bank-good-2", "20.00", LocalDate.of(2026, 9, 2))));

        JsonNode log = Json.data(sync().body());

        assertThat(log.path("status").asString()).isEqualTo("PARTIAL_FAILURE");
        assertThat(log.path("transactionsFetched").asInt()).isEqualTo(3);
        assertThat(log.path("transactionsNew").asInt()).isEqualTo(2);
        assertThat(log.path("transactionsFailed").asInt()).isEqualTo(1);
        assertThat(log.path("errorMessage").asString()).contains("could not be accepted");
        assertThat(transactionIds(admin, "accountId=" + accountId))
            .as("the accepted rows are committed; the refused one cost a counter")
            .hasSize(2);
        assertThat(accountSyncStatus())
            .as("a partial failure still reached the source, so the account is OK")
            .isEqualTo("OK");
    }

    @Test
    @DisplayName("An unreachable source is a FAILED log and a released claim, not an HTTP error")
    void aConnectionFailureIsAnOutcome() {
        source.failingWith("The bridge refused these credentials.");

        HttpResponse<String> response = sync();

        assertThat(response.statusCode())
            .as("the request succeeded in recording what happened; a member needs to be told "
                + "their credentials expired, not shown a 500")
            .isEqualTo(200);
        JsonNode log = Json.data(response.body());
        assertThat(log.path("status").asString()).isEqualTo("FAILED");
        assertThat(log.path("errorMessage").asString())
            .isEqualTo("The bridge refused these credentials.");
        assertThat(accountSyncStatus())
            .as("the claim must be released on every path, or the account can never be synced "
                + "again and there is no screen anywhere that would clear it")
            .isEqualTo("ERROR");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT last_synced_at FROM connected_account WHERE id = ?", Instant.class,
            UUID.fromString(accountId)))
            .as("a run that never reached its source must not move last_synced_at, or the next "
                + "run fetches from a window it never actually read")
            .isNull();
    }

    @Test
    @DisplayName("A fetch that succeeds and a batch write that then fails stores nothing AND "
        + "leaves last_synced_at alone — otherwise the fetched window is skipped for ever")
    void aFailedBatchWriteDoesNotStampTheWindow() {
        // The row is well formed as far as ingestion is concerned — it has a date, an amount and
        // a type, so RawTransactionRejectedException is NOT the path taken — but its merchant
        // name overflows transaction.merchant_name VARCHAR(255). The failure therefore surfaces
        // where the finding says it does: out of IngestionBatchWriter.persist(), which is
        // @Transactional, so the WHOLE batch rolls back and not one row is kept.
        //
        // Before the fix the orchestrator had already set `reachedSource = true` on the line
        // after fetch() returned, so the finally block stamped last_synced_at anyway. The next
        // run's window then started at now() - OVERLAP_DAYS, and every transaction in the window
        // that was read but never stored — up to the full 90-day first-sync lookback — was never
        // requested again. Silent, permanent absence from a financial ledger.
        source.returning(List.of(RawTransaction.of("bank-oversize", "ext-account",
            new BigDecimal("10.00"), "CAD", "A description", "M".repeat(300),
            LocalDate.of(2026, 9, 1), Instant.now(), TransactionType.DEBIT, false)));

        HttpResponse<String> response = sync();

        assertThat(response.statusCode())
            .as("a failed run is still an outcome the member is told about, not a 500")
            .isEqualTo(200);
        JsonNode log = Json.data(response.body());
        assertThat(log.path("status").asString()).isEqualTo("FAILED");
        assertThat(log.path("transactionsFetched").asInt())
            .as("FAILED reports what the run READ; the counters below say what it kept")
            .isEqualTo(1);
        assertThat(log.path("transactionsNew").asInt()).isZero();
        assertThat(log.path("transactionsUpdated").asInt()).isZero();
        assertThat(transactionIds(admin, "accountId=" + accountId))
            .as("the batch is one transaction and it rolled back, so nothing was stored")
            .isEmpty();
        assertThat(accountSyncStatus())
            .as("the claim is released on every path, and the account carries the failure")
            .isEqualTo("ERROR");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT last_synced_at FROM connected_account WHERE id = ?", Instant.class,
            UUID.fromString(accountId)))
            .as("nothing was durably stored, so the next run must ask for this window again; "
                + "stamping here loses every fetched-but-unwritten row for ever")
            .isNull();
    }

    @Test
    @DisplayName("The sync history is newest first and shows every run")
    void theSyncHistoryAccumulates() {
        source.returning(List.of(row("bank-1", "10.00", LocalDate.of(2026, 9, 1))));
        sync();
        source.failingWith("A later failure.");
        sync();

        List<JsonNode> logs = Json.elements(Json.data(
            get(appUrl("/v1/accounts/" + accountId + "/sync-logs"), admin.accessToken()).body()));

        assertThat(logs).hasSize(2);
        assertThat(logs.get(0).path("status").asString()).isEqualTo("FAILED");
        assertThat(logs.get(1).path("status").asString()).isEqualTo("SUCCESS");
        assertThat(logs.get(0).path("accountId").asString()).isEqualTo(accountId);
    }

    @Test
    @DisplayName("Two threads sync one account: exactly one takes the claim, the other gets 409")
    void theSyncClaimHoldsUnderConcurrency() throws Exception {
        source.returning(List.of(row("bank-1", "10.00", LocalDate.of(2026, 9, 1))));
        CountDownLatch fetchStarted = source.parkNextFetch();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch bothReady = new CountDownLatch(2);
            Future<HttpResponse<String>> first = pool.submit(() -> {
                bothReady.countDown();
                bothReady.await(10, TimeUnit.SECONDS);
                return sync();
            });
            Future<HttpResponse<String>> second = pool.submit(() -> {
                bothReady.countDown();
                bothReady.await(10, TimeUnit.SECONDS);
                return sync();
            });

            // The winner parks inside the source with the claim committed, so the loser's attempt
            // provably happens while the claim is genuinely held rather than by luck of timing.
            assertThat(fetchStarted.await(30, TimeUnit.SECONDS))
                .as("one of the two threads must have reached the source")
                .isTrue();
            awaitEitherDone(first, second);
            source.releaseFetch();

            List<Integer> statuses = List.of(
                first.get(60, TimeUnit.SECONDS).statusCode(),
                second.get(60, TimeUnit.SECONDS).statusCode());

            assertThat(statuses)
                .as("exactly one caller may hold the claim; the other is told so. A 401 here "
                    + "would mean the race harness, not the guard, is what failed")
                .containsExactlyInAnyOrder(200, 409);
        } finally {
            source.releaseFetch();
            pool.shutdownNow();
        }

        assertThat(source.fetchCount())
            .as("the loser must not have fetched — that is the collision the claim prevents")
            .isEqualTo(1);
        assertThat(accountSyncStatus()).isEqualTo("OK");
        assertThat(transactionIds(admin, "accountId=" + accountId)).hasSize(1);
    }

    @Test
    @DisplayName("A first sync looks back 90 days; a later one overlaps the last window")
    void theFetchWindowIsBoundedAndOverlaps() {
        source.returning(List.of());
        sync();

        assertThat(source.lastRequestedFrom())
            .isEqualTo(source.lastRequestedTo().minusDays(SyncOrchestrator.FIRST_SYNC_LOOKBACK_DAYS));

        source.returning(List.of());
        sync();

        assertThat(source.lastRequestedFrom())
            .as("a bank can report a transaction days after its own date, so a window that began "
                + "exactly where the last one ended would miss it for ever")
            .isAfterOrEqualTo(LocalDate.now().minusDays(SyncOrchestrator.OVERLAP_DAYS + 2));
    }

    private HttpResponse<String> sync() {
        return postJson(appUrl("/v1/accounts/" + accountId + "/sync"), null,
            "Authorization", "Bearer " + admin.accessToken());
    }

    private String accountSyncStatus() {
        return jdbcTemplate.queryForObject(
            "SELECT sync_status FROM connected_account WHERE id = ?", String.class,
            UUID.fromString(accountId));
    }

    private static void awaitEitherDone(Future<?> first, Future<?> second)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!first.isDone() && !second.isDone() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static RawTransaction row(String providerId, String amount, LocalDate date) {
        return RawTransaction.of(providerId, "ext-account", new BigDecimal(amount), "CAD",
            "A description", "A Merchant", date, Instant.now(), TransactionType.DEBIT, false);
    }

    private static RawTransaction pending(String providerId, String amount, LocalDate date) {
        return RawTransaction.of(providerId, "ext-account", new BigDecimal(amount), "CAD",
            "A description", "A Merchant", date, null, TransactionType.DEBIT, true);
    }
}
