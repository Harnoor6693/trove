package finance.trove.ingestion.dedup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.accounts.AccountType;
import finance.trove.accounts.ConnectedAccount;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.RawTransaction;
import finance.trove.common.plugin.SourceKind;
import finance.trove.common.plugin.TransactionType;
import finance.trove.ingestion.ParsedRow;
import finance.trove.ingestion.RawTransactionRejectedException;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionFixtures;
import finance.trove.transactions.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Both deduplication passes, and one failing-without-the-fix test for each defect named in
 * {@code docs/recovery/06-COLLAPSE-PLAN.md} §2.2 and §6 M0.
 *
 * <p>The repository is mocked here on purpose: what is under test is the DECISION — which pass
 * runs, against which candidates, and what it concludes — not the SQL. The queries themselves are
 * proved end to end by {@code SyncOrchestratorIT} against a Flyway-migrated Postgres, which is the
 * split {@code docs/recovery/inventory/categorisation-service.md} §9 argues for after a mocked
 * test failed to catch a query-direction bug.
 */
class DeduplicationServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 4);

    private TransactionRepository transactions;
    private DeduplicationService service;
    private ConnectedAccount account;

    @BeforeEach
    void setUp() {
        transactions = mock(TransactionRepository.class);
        service = new DeduplicationService(transactions);
        account = account(CurrencyCode.CAD);
        when(transactions.save(any(Transaction.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.findByAccountIdAndProviderTransactionId(any(), any()))
            .thenReturn(Optional.empty());
        when(transactions.findFuzzyCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of());
    }

    // ------------------------------------------------------------------ the exact pass

    @Test
    @DisplayName("An unseen provider id is inserted")
    void anUnseenProviderIdIsNew() {
        RawTransaction incoming = raw("bank-1", "12.34", DAY, false);

        assertThat(service.deduplicate(account, USER, incoming, DedupBatch.of(incoming)))
            .isEqualTo(DeduplicationResult.NEW);
        verify(transactions).save(any(Transaction.class));
    }

    @Test
    @DisplayName("The same provider id arriving again is skipped, not stored twice")
    void aRepeatedProviderIdIsSkipped() {
        RawTransaction incoming = raw("bank-1", "12.34", DAY, false);
        when(transactions.findByAccountIdAndProviderTransactionId(account.getId(), "bank-1"))
            .thenReturn(Optional.of(stored("bank-1", "12.34", DAY, false)));

        assertThat(service.deduplicate(account, USER, incoming, DedupBatch.of(incoming)))
            .isEqualTo(DeduplicationResult.SKIPPED);
        verify(transactions, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("A pending row that has posted is promoted in place, keeping its enrichment")
    void aPendingRowThatPostedIsUpdated() {
        Transaction existing = stored("bank-1", "12.34", DAY, true);
        existing.setMerchantName("A merchant the member corrected");
        existing.setNotes("A note the member wrote");
        when(transactions.findByAccountIdAndProviderTransactionId(account.getId(), "bank-1"))
            .thenReturn(Optional.of(existing));
        RawTransaction incoming = raw("bank-1", "12.34", DAY, false);

        assertThat(service.deduplicate(account, USER, incoming, DedupBatch.of(incoming)))
            .isEqualTo(DeduplicationResult.UPDATED);
        assertThat(existing.isPending()).isFalse();
        assertThat(existing.getPostedAt()).isNotNull();
        assertThat(existing.getMerchantName())
            .as("a re-sync promotes a pending row; it must not reassert the source's view of "
                + "fields the member has since corrected")
            .isEqualTo("A merchant the member corrected");
        assertThat(existing.getNotes()).isEqualTo("A note the member wrote");
    }

    // ------------------------------------------------------------------ defect 1

    @Test
    @DisplayName("DEFECT 1 (§6 M0): a row with its own provider id never reaches the fuzzy window, "
        + "so two same-day same-amount pending charges cannot collapse into one")
    void aRowWithItsOwnProviderIdNeverFallsIntoTheFuzzyWindow() {
        // The stored row is a DIFFERENT transaction that happens to be pending, same day, same
        // amount — a second coffee at the same shop. The old pass matched any pending row in the
        // window regardless of the candidate's own identity, promoted this one, and the member's
        // second purchase silently never existed.
        Transaction somebodyElse = stored("bank-EARLIER", "12.34", DAY, true);
        when(transactions.findFuzzyCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(somebodyElse));
        RawTransaction incoming = raw("bank-LATER", "12.34", DAY, false);

        assertThat(service.deduplicate(account, USER, incoming, DedupBatch.of(incoming)))
            .as("the incoming row states its own identity, and the exact lookup missed, so it is "
                + "a transaction Trove has not seen — not a near-match of a different one")
            .isEqualTo(DeduplicationResult.NEW);
        assertThat(somebodyElse.isPending())
            .as("the unrelated pending row must not have been promoted")
            .isTrue();
        verify(transactions, never()).findFuzzyCandidates(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("DEFECT 1, second half: an id-less row skips a candidate this very batch is "
        + "already reconciling by its exact id")
    void aCandidateThisBatchAlreadyCarriesIsNotAFuzzyMatch() {
        Transaction candidate = stored("bank-IN-THIS-BATCH", "12.34", DAY, true);
        when(transactions.findFuzzyCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(candidate));

        RawTransaction exactRow = raw("bank-IN-THIS-BATCH", "12.34", DAY, false);
        RawTransaction idLessRow = raw(null, "12.34", DAY, false);
        DedupBatch batch = new DedupBatch(List.of(exactRow, idLessRow));

        assertThat(service.deduplicate(account, USER, idLessRow, batch))
            .as("that stored row belongs to the other row of this same batch")
            .isEqualTo(DeduplicationResult.NEW);
        assertThat(candidate.isPending()).isTrue();
    }

    @Test
    @DisplayName("DEFECT 1, third half: one stored row cannot satisfy two id-less incoming rows")
    void aCandidateIsConsumedByTheFirstRowThatMatchesIt() {
        Transaction candidate = stored(null, "12.34", DAY, true);
        when(transactions.findFuzzyCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(candidate));

        RawTransaction first = raw(null, "12.34", DAY, false);
        RawTransaction second = raw(null, "12.34", DAY, false);
        DedupBatch batch = new DedupBatch(List.of(first, second));

        assertThat(service.deduplicate(account, USER, first, batch))
            .isEqualTo(DeduplicationResult.UPDATED);
        assertThat(service.deduplicate(account, USER, second, batch))
            .as("the candidate is spoken for; the second row is a transaction of its own")
            .isEqualTo(DeduplicationResult.NEW);
    }

    // ------------------------------------------------------------------ defect 2

    @Test
    @DisplayName("DEFECT 2 (§2.2): a re-imported POSTED row matches a POSTED candidate and is "
        + "skipped — the old pass demanded pending=true and duplicated every row of the file")
    void aReimportedPostedRowIsSkippedRatherThanDuplicated() {
        Transaction alreadyPosted = stored(null, "12.34", DAY, false);
        when(transactions.findFuzzyCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of(alreadyPosted));
        RawTransaction incoming = raw(null, "12.34", DAY, false);

        assertThat(service.deduplicate(account, USER, incoming, DedupBatch.of(incoming)))
            .as("the old fuzzy query filtered on pending=true, so a re-uploaded CSV matched "
                + "nothing and every row of it was inserted a second time")
            .isEqualTo(DeduplicationResult.SKIPPED);
        verify(transactions, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("An id-less row with no candidate at all is inserted")
    void anIdLessRowWithNoCandidateIsNew() {
        RawTransaction incoming = raw(null, "12.34", DAY, false);

        assertThat(service.deduplicate(account, USER, incoming, DedupBatch.of(incoming)))
            .isEqualTo(DeduplicationResult.NEW);
    }

    @Test
    @DisplayName("The fuzzy window is +/-2 days and +/-0.50 around the incoming row")
    void theFuzzyWindowIsBoundedAsSpecified() {
        RawTransaction incoming = raw(null, "12.34", DAY, false);

        service.deduplicate(account, USER, incoming, DedupBatch.of(incoming));

        verify(transactions).findFuzzyCandidates(eq(account.getId()), eq(DAY.minusDays(2)),
            eq(DAY.plusDays(2)), eq(new BigDecimal("11.84")), eq(new BigDecimal("12.84")));
    }

    // ------------------------------------------------------------------ F4: no literal fallbacks

    @Test
    @DisplayName("F4: a row with no currency takes the ACCOUNT's, never a 'CAD' literal")
    void currencyComesFromTheAccountWhenTheRowHasNone() {
        ConnectedAccount american = account(CurrencyCode.USD);
        RawTransaction incoming = new RawTransaction("bank-1", "ext", new BigDecimal("12.34"),
            null, "desc", "merchant", DAY, null, TransactionType.DEBIT, false, null);

        service.deduplicate(american, USER, incoming, DedupBatch.of(incoming));

        verify(transactions).save(argThatCurrencyIs(CurrencyCode.USD));
    }

    @Test
    @DisplayName("F4: a currency this install does not support is refused, not reinterpreted")
    void anUnsupportedCurrencyIsRejected() {
        RawTransaction incoming = new RawTransaction("bank-1", "ext", new BigDecimal("12.34"),
            "GBP", "desc", "merchant", DAY, null, TransactionType.DEBIT, false, null);

        assertThatThrownBy(
            () -> service.deduplicate(account, USER, incoming, DedupBatch.of(incoming)))
            .isInstanceOf(RawTransactionRejectedException.class)
            .hasMessageContaining("GBP");
        verify(transactions, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("F4: a row with no type is refused rather than assumed to be a DEBIT")
    void aRowWithNoTypeIsRejected() {
        RawTransaction incoming = new RawTransaction("bank-1", "ext", new BigDecimal("12.34"),
            "CAD", "desc", "merchant", DAY, null, null, false, null);

        assertThatThrownBy(
            () -> service.deduplicate(account, USER, incoming, DedupBatch.of(incoming)))
            .isInstanceOf(RawTransactionRejectedException.class);
    }

    @Test
    @DisplayName("B1-04: an exponent bomb is refused at the gate, in milliseconds, before normalise")
    void anAmountTheColumnCannotHoldIsRejectedCheaply() {
        RawTransaction bomb = new RawTransaction("bank-1", "ext", new BigDecimal("1E9999999"),
            "CAD", "desc", "merchant", DAY, null, TransactionType.DEBIT, false, null);
        RawTransaction fifthDecimal = new RawTransaction("bank-2", "ext",
            new BigDecimal("1.23456"), "CAD", "desc", "merchant", DAY, null,
            TransactionType.DEBIT, false, null);

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.deduplicate(account, USER, bomb, DedupBatch.of(bomb)))
            .isInstanceOf(RawTransactionRejectedException.class)
            .hasMessageContaining("amount");
        assertThatThrownBy(() -> service.classify(account, bomb, DedupBatch.of(bomb)))
            .isInstanceOf(RawTransactionRejectedException.class);
        assertThat(System.nanoTime() - started).isLessThan(100_000_000L);
        assertThatThrownBy(
            () -> service.deduplicate(account, USER, fifthDecimal, DedupBatch.of(fifthDecimal)))
            .isInstanceOf(RawTransactionRejectedException.class);
        verify(transactions, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("B1-13: a row the file's parser could not read is refused at commit for that reason")
    void aRowTheParserRefusedIsRefusedAtCommit() {
        RawTransaction noFitId = new ParsedRow(2, new RawTransaction(null, "ext",
            new BigDecimal("9.99"), "CAD", "desc", "merchant", DAY, null, TransactionType.DEBIT,
            false, null), "this transaction has no FITID").toPipeline();

        assertThatThrownBy(() -> service.deduplicate(account, USER, noFitId,
            DedupBatch.of(noFitId)))
            .as("complete enough for the fuzzy pass, which would have inserted a row the preview "
                + "showed as INVALID")
            .isInstanceOf(RawTransactionRejectedException.class)
            .hasMessage("this transaction has no FITID");
        verify(transactions, never()).save(any(Transaction.class));
    }

    @Test
    @DisplayName("A row with no date or no amount is refused BEFORE anything is written")
    void anIncompleteRowIsRejectedBeforeAnyWrite() {
        RawTransaction noDate = new RawTransaction("bank-1", "ext", new BigDecimal("1.00"), "CAD",
            null, null, null, null, TransactionType.DEBIT, false, null);
        RawTransaction noAmount = new RawTransaction("bank-2", "ext", null, "CAD", null, null, DAY,
            null, TransactionType.DEBIT, false, null);

        assertThatThrownBy(() -> service.deduplicate(account, USER, noDate, DedupBatch.of(noDate)))
            .isInstanceOf(RawTransactionRejectedException.class);
        assertThatThrownBy(
            () -> service.deduplicate(account, USER, noAmount, DedupBatch.of(noAmount)))
            .isInstanceOf(RawTransactionRejectedException.class);

        verify(transactions, never()).save(any(Transaction.class));
        verify(transactions, never()).findByAccountIdAndProviderTransactionId(any(), any());
    }

    @Test
    @DisplayName("A negative amount is stored as its absolute value; the type carries direction")
    void amountsAreStoredUnsigned() {
        RawTransaction incoming = new RawTransaction("bank-1", "ext", new BigDecimal("-12.34"),
            "CAD", null, null, DAY, null, TransactionType.DEBIT, false, null);

        service.deduplicate(account, USER, incoming, DedupBatch.of(incoming));

        verify(transactions).save(argThatAmountIs(new BigDecimal("12.3400")));
    }

    // ------------------------------------------------------------------ helpers

    private static Transaction argThatCurrencyIs(CurrencyCode expected) {
        return org.mockito.ArgumentMatchers.argThat(
            candidate -> candidate != null && candidate.getCurrency() == expected);
    }

    private static Transaction argThatAmountIs(BigDecimal expected) {
        return org.mockito.ArgumentMatchers.argThat(
            candidate -> candidate != null && candidate.getAmount().compareTo(expected) == 0);
    }

    @Test
    @DisplayName("a whole file's exact matches are loaded in chunks, not one query per row")
    void theExactMatchIndexIsLoadedInChunks() {
        List<RawTransaction> rows = new java.util.ArrayList<>();
        for (int i = 0; i < DeduplicationService.CHUNK + 7; i++) {
            rows.add(raw("p-" + i, "1.00", DAY, false));
        }
        rows.add(RawTransaction.of(null, "ext-account", new BigDecimal("9.99"), "CAD", "No id",
            null, DAY, Instant.now(), TransactionType.DEBIT, false));
        Transaction held = stored("p-3", "1.00", DAY, false);
        when(transactions.findAllByAccountIdAndProviderTransactionIdIn(eq(account.getId()), any()))
            .thenAnswer(invocation -> {
                java.util.Collection<String> ids = invocation.getArgument(1);
                assertThat(ids.size())
                    .as("a bind list of fifty thousand parameters is refused by the driver")
                    .isLessThanOrEqualTo(DeduplicationService.CHUNK);
                return ids.contains("p-3") ? List.of(held) : List.<Transaction>of();
            });

        java.util.Map<String, Transaction> index = service.exactMatchIndex(account, rows);

        assertThat(index).containsOnlyKeys("p-3");
        verify(transactions, org.mockito.Mockito.times(2))
            .findAllByAccountIdAndProviderTransactionIdIn(eq(account.getId()), any());
        verify(transactions, never()).findByAccountIdAndProviderTransactionId(any(), any());
    }

    @Test
    @DisplayName("classifying against the index answers exactly what the per-row query answers")
    void theIndexedClassificationAgreesWithTheUnindexedOne() {
        RawTransaction held = raw("p-held", "1.00", DAY, false);
        RawTransaction fresh = raw("p-fresh", "2.00", DAY, false);
        Transaction stored = stored("p-held", "1.00", DAY, false);
        when(transactions.findByAccountIdAndProviderTransactionId(account.getId(), "p-held"))
            .thenReturn(Optional.of(stored));
        when(transactions.findAllByAccountIdAndProviderTransactionIdIn(eq(account.getId()), any()))
            .thenReturn(List.of(stored));

        java.util.Map<String, Transaction> index =
            service.exactMatchIndex(account, List.of(held, fresh));

        assertThat(service.classify(account, held, new DedupBatch(List.of(held, fresh)), index))
            .isEqualTo(service.classify(account, held, new DedupBatch(List.of(held, fresh))))
            .isEqualTo(DeduplicationResult.SKIPPED);
        assertThat(service.classify(account, fresh, new DedupBatch(List.of(held, fresh)), index))
            .isEqualTo(service.classify(account, fresh, new DedupBatch(List.of(held, fresh))))
            .isEqualTo(DeduplicationResult.NEW);
    }

    @Test
    @DisplayName("a row with no provider id still goes to the fuzzy pass, index or no index")
    void theIndexDoesNotStandInForTheFuzzyPass() {
        RawTransaction noId = RawTransaction.of(null, "ext-account", new BigDecimal("9.99"), "CAD",
            "No id", null, DAY, Instant.now(), TransactionType.DEBIT, false);
        when(transactions.findFuzzyCandidates(any(), any(), any(), any(), any()))
            .thenReturn(List.of());

        assertThat(service.classify(account, noId, new DedupBatch(List.of(noId)),
            java.util.Map.of())).isEqualTo(DeduplicationResult.NEW);

        verify(transactions).findFuzzyCandidates(eq(account.getId()), any(), any(), any(), any());
    }

    private static ConnectedAccount account(CurrencyCode currency) {
        ConnectedAccount account = new ConnectedAccount(HOUSEHOLD, USER, SourceKind.SIMPLEFIN,
            "Test account", null, AccountType.CHECKING, currency, Instant.now());
        TransactionFixtures.withId(account, UUID.randomUUID());
        return account;
    }

    private static RawTransaction raw(String providerId, String amount, LocalDate date,
                                      boolean pending) {
        return RawTransaction.of(providerId, "ext-account", new BigDecimal(amount), "CAD",
            "A description", "A Merchant", date, pending ? null : Instant.now(),
            TransactionType.DEBIT, pending);
    }

    private static Transaction stored(String providerId, String amount, LocalDate date,
                                      boolean pending) {
        Transaction transaction = new Transaction(HOUSEHOLD, UUID.randomUUID(), USER, providerId,
            new BigDecimal(amount), CurrencyCode.CAD, TransactionType.DEBIT, "A description",
            "A Merchant", date, pending ? null : Instant.now(), pending, false, Instant.now());
        TransactionFixtures.withId(transaction, UUID.randomUUID());
        return transaction;
    }

}
