package finance.trove.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.accounts.AccountService;
import finance.trove.accounts.AccountType;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.accounts.SyncStatus;
import finance.trove.common.crypto.FieldEncryptionService;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.BankSource;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.plugin.TransactionType;
import finance.trove.common.time.UserClock;
import finance.trove.common.time.UserTimeZoneProvider;
import finance.trove.transactions.TransactionFixtures;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

/**
 * The one invariant of {@link SyncOrchestrator} that decides whether a window can be lost.
 *
 * <p>{@code last_synced_at} is what the NEXT run's window is measured from, so whatever moves it
 * is a claim that everything up to that instant is safely in the ledger. The batch write is a
 * transaction of its own: reaching the source and <em>keeping</em> what it gave are two different
 * events, and only the second may stamp. A run whose fetch succeeded and whose
 * {@link IngestionBatchWriter#persist} then threw has written nothing at all — the whole batch
 * rolled back — yet it plainly reached its source. Stamping on that path moves the next window
 * past every row that was read and never stored, and because the exact-match key never sees them
 * again they are silently and permanently absent from a financial ledger.
 *
 * <p>{@code SyncOrchestratorIT#aFailedBatchWriteDoesNotStampTheWindow} proves the same thing
 * against a real Postgres, by overflowing {@code merchant_name}. This asserts it at the seam
 * itself, against the exact argument {@link SyncStateWriter#release} is given, so a future
 * refactor that reintroduces a separate "did we reach the source?" flag fails here in
 * milliseconds and names the reason.
 */
@ExtendWith(MockitoExtension.class)
class SyncOrchestratorTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    @Mock
    private AccountService accounts;

    @Mock
    private SyncLogRepository logs;

    @Mock
    private SyncStateWriter state;

    @Mock
    private IngestionBatchWriter batchWriter;

    @Mock
    private FieldEncryptionService encryption;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ApplicationEventPublisher events;

    private final UserClock clock = new UserClock(
        (UserTimeZoneProvider) userId -> ZoneId.of("UTC"),
        Clock.fixed(Instant.parse("2026-09-04T12:00:00Z"), ZoneOffset.UTC));

    /** A source that always answers with one perfectly ordinary row. */
    private final BankSource source = new BankSource() {
        @Override
        public SourceKind sourceKind() {
            return SourceKind.SIMPLEFIN;
        }

        @Override
        public List<RawTransaction> fetch(String externalAccountId, LocalDate from, LocalDate to,
                                          Map<String, String> config) {
            return List.of(RawTransaction.of("bank-1", "ext-account", new BigDecimal("10.00"),
                "CAD", "A description", "A Merchant", LocalDate.of(2026, 9, 1), Instant.now(),
                TransactionType.DEBIT, false));
        }
    };

    private SyncOrchestrator orchestrator() {
        return new SyncOrchestrator(accounts, logs, new SourceRegistry(List.of(source)), state,
            batchWriter, encryption, objectMapper, events, clock);
    }

    private ConnectedAccount account() {
        ConnectedAccount account = new ConnectedAccount(HOUSEHOLD, USER, SourceKind.SIMPLEFIN,
            "Chequing", "A Bank", AccountType.CHECKING, CurrencyCode.CAD, clock.now());
        TransactionFixtures.withId(account, ACCOUNT);
        account.setExternalAccountId("ext-account");
        return account;
    }

    private void aClaimedRun() {
        ConnectedAccount account = account();
        when(accounts.get(HOUSEHOLD, ACCOUNT)).thenReturn(account);
        when(state.claim(HOUSEHOLD, ACCOUNT)).thenReturn(true);
        SyncLog opened = TransactionFixtures.withId(
            new SyncLog(HOUSEHOLD, ACCOUNT, USER, SourceKind.SIMPLEFIN, clock.now()),
            UUID.randomUUID());
        when(state.start(any(), eq(USER), any())).thenReturn(opened);
        when(state.finish(eq(HOUSEHOLD), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
            anyInt(), any())).thenReturn(opened);
    }

    @Test
    @DisplayName("A batch write that throws must NOT move last_synced_at — the fetched window "
        + "would otherwise never be asked for again")
    void aFailedBatchWriteNeverStampsTheWindow() {
        aClaimedRun();
        when(batchWriter.persist(any(), eq(USER), any(), isNull()))
            .thenThrow(new DataIntegrityViolationException("value too long for merchant_name"));

        SyncLog finished = orchestrator().sync(HOUSEHOLD, USER, ACCOUNT);

        assertThat(finished).isNotNull();
        verify(state).release(eq(HOUSEHOLD), eq(ACCOUNT), eq(SyncStatus.ERROR), anyString(),
            eq(false));
        verify(state).finish(eq(HOUSEHOLD), any(), eq(SyncRunStatus.FAILED), eq(1), eq(0), eq(0),
            eq(0), eq(0), anyString());
    }

    @Test
    @DisplayName("A batch write that commits does move last_synced_at, and reports SUCCESS")
    void aCommittedBatchStampsTheWindow() {
        aClaimedRun();
        when(batchWriter.persist(any(), eq(USER), any(), isNull())).thenReturn(
            new IngestionBatch(List.of(UUID.randomUUID()), List.of(), 0, 0, null));

        orchestrator().sync(HOUSEHOLD, USER, ACCOUNT);

        verify(state).release(eq(HOUSEHOLD), eq(ACCOUNT), eq(SyncStatus.OK), isNull(), eq(true));
        verify(state).finish(eq(HOUSEHOLD), any(), eq(SyncRunStatus.SUCCESS), eq(1), eq(1), eq(0),
            eq(0), eq(0), isNull());
    }

    @Test
    @DisplayName("A partial failure still stored rows, so it stamps too")
    void aPartialFailureStampsTheWindow() {
        aClaimedRun();
        when(batchWriter.persist(any(), eq(USER), any(), isNull())).thenReturn(
            new IngestionBatch(List.of(UUID.randomUUID()), List.of(), 0, 1, "1 row(s) refused"));

        orchestrator().sync(HOUSEHOLD, USER, ACCOUNT);

        verify(state).release(eq(HOUSEHOLD), eq(ACCOUNT), eq(SyncStatus.OK), isNull(), eq(true));
        verify(state).finish(eq(HOUSEHOLD), any(), eq(SyncRunStatus.PARTIAL_FAILURE), eq(1), eq(1),
            eq(0), eq(0), eq(1), anyString());
    }
}
