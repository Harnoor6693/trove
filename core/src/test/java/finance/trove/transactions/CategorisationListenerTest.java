package finance.trove.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.categorisation.BatchCategoriser;
import finance.trove.categorisation.CategorisationResult;
import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.events.TransactionsIngestedEvent;
import finance.trove.common.plugin.TransactionType;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.interceptor.TransactionAttribute;

/**
 * What the ingest categorisation listener asks for, and in what order — collapse step 16 wire 1.
 *
 * <p>The two facts here are the ones a seam IT cannot show, because they are about what the
 * listener <em>passes</em> rather than about the row that comes out: that the owner named to
 * {@code BatchCategoriser} is the TRANSACTION OWNER from the event and never anybody else, and
 * that the batch is resolved BEFORE the write-back is entered. The end-to-end proof that the
 * column really changes is {@code CategorisationSeamIT}'s.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CategorisationListenerTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final UUID CATEGORY = UUID.randomUUID();

    @Mock
    private TransactionQueryService transactions;

    @Mock
    private TransactionCategoriser categoriser;

    @Mock
    private BatchCategoriser batch;

    @Mock
    private CategorisationWriteBack writeBack;

    private CategorisationListener listener() {
        return new CategorisationListener(transactions, categoriser, batch, writeBack);
    }

    private static Transaction row(UUID id, String merchant) {
        Transaction transaction = new Transaction(HOUSEHOLD, ACCOUNT, OWNER, "provider-" + id,
            new BigDecimal("12.00"), CurrencyCode.CAD, TransactionType.DEBIT, "A description",
            merchant, LocalDate.of(2026, 9, 4), Instant.parse("2026-09-04T12:00:00Z"), false,
            false, Instant.parse("2026-09-04T12:00:00Z"));
        ReflectionTestUtils.setField(transaction, "id", id);
        return transaction;
    }

    private static TransactionsIngestedEvent ingested(List<UUID> newIds) {
        return new TransactionsIngestedEvent(HOUSEHOLD, OWNER, ACCOUNT, newIds, List.of());
    }

    private static BatchCategoriser.Outcome outcome(Map<UUID, CategorisationResult> results,
                                                    int attempted, int atCap) {
        return new BatchCategoriser.Outcome(results, attempted, atCap);
    }

    @Test
    @DisplayName("the owner passed to the batch is the event's userId — the row owner, never a "
        + "caller")
    void theBatchIsAskedOnBehalfOfTheRowOwner() {
        UUID id = UUID.randomUUID();
        when(transactions.findAllByIds(eq(HOUSEHOLD), anyList()))
            .thenReturn(List.of(row(id, "A MERCHANT")));
        when(batch.categorise(any(), any(), anyList())).thenReturn(outcome(Map.of(), 0, 0));

        listener().onTransactionsIngested(ingested(List.of(id)));

        ArgumentCaptor<UUID> owner = ArgumentCaptor.forClass(UUID.class);
        verify(batch).categorise(eq(HOUSEHOLD), owner.capture(), anyList());
        assertThat(owner.getValue())
            .as("ai_settings is keyed by user id, so the owner is the only member whose AI may be "
                + "spent on this row")
            .isEqualTo(OWNER);
    }

    @Test
    @DisplayName("the batch is resolved before the write transaction is entered")
    void theRoundTripHappensBeforeTheWrite() {
        UUID id = UUID.randomUUID();
        when(transactions.findAllByIds(eq(HOUSEHOLD), anyList()))
            .thenReturn(List.of(row(id, "A MERCHANT")));
        BatchCategoriser.Outcome resolved = outcome(
            Map.of(id, new CategorisationResult(CATEGORY, "Groceries", new BigDecimal("0.9"),
                null)), 1, 0);
        when(batch.categorise(any(), any(), anyList())).thenReturn(resolved);

        listener().onTransactionsIngested(ingested(List.of(id)));

        InOrder order = inOrder(batch, writeBack);
        order.verify(batch).categorise(eq(HOUSEHOLD), eq(OWNER), anyList());
        order.verify(writeBack).apply(any(), eq(resolved.results()));
        order.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("a pinned row is never even asked about, so it cannot cost an AI call")
    void aPinnedRowIsDroppedFromTheBatch() {
        UUID pinned = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        when(transactions.findAllByIds(eq(HOUSEHOLD), anyList()))
            .thenReturn(List.of(row(pinned, "PINNED"), row(free, "FREE")));
        when(categoriser.isPinned(HOUSEHOLD, pinned)).thenReturn(true);
        when(batch.categorise(any(), any(), anyList())).thenReturn(outcome(Map.of(), 0, 0));

        listener().onTransactionsIngested(ingested(List.of(pinned, free)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BatchCategoriser.Item>> items =
            ArgumentCaptor.forClass(List.class);
        verify(batch).categorise(eq(HOUSEHOLD), eq(OWNER), items.capture());
        assertThat(items.getValue()).extracting(BatchCategoriser.Item::transactionId)
            .containsExactly(free);
    }

    @Test
    @DisplayName("an ingest with no new rows does no work at all")
    void updatedOnlyIngestionIsANoOp() {
        listener().onTransactionsIngested(
            new TransactionsIngestedEvent(HOUSEHOLD, OWNER, ACCOUNT, List.of(),
                List.of(UUID.randomUUID())));

        verify(transactions, never()).findAllByIds(any(), anyList());
        verify(batch, never()).categorise(any(), any(), anyList());
        verify(writeBack, never()).apply(any(), any());
    }

    @Test
    @DisplayName("a batch of nothing but pinned rows never reaches the categoriser")
    void anEntirelyPinnedBatchStopsEarly() {
        UUID pinned = UUID.randomUUID();
        when(transactions.findAllByIds(eq(HOUSEHOLD), anyList()))
            .thenReturn(List.of(row(pinned, "PINNED")));
        when(categoriser.isPinned(HOUSEHOLD, pinned)).thenReturn(true);

        listener().onTransactionsIngested(ingested(List.of(pinned)));

        verify(batch, never()).categorise(any(), any(), anyList());
        verify(writeBack, never()).apply(any(), any());
    }

    @Test
    @DisplayName("a failure in the categoriser never reaches the member's committed write")
    void aFailureIsSwallowed() {
        UUID id = UUID.randomUUID();
        when(transactions.findAllByIds(eq(HOUSEHOLD), anyList()))
            .thenReturn(List.of(row(id, "A MERCHANT")));
        when(batch.categorise(any(), any(), anyList()))
            .thenThrow(new IllegalStateException("the tier is down"));

        assertThatCode(() -> listener().onTransactionsIngested(ingested(List.of(id))))
            .as("the rows are already committed; turning this into an error would make a "
                + "successful sync look failed")
            .doesNotThrowAnyException();
        verify(writeBack, never()).apply(any(), any());
    }

    @Test
    @DisplayName("the cap report is reached even when nothing changed")
    void theCapCountIsReported() {
        UUID id = UUID.randomUUID();
        when(transactions.findAllByIds(eq(HOUSEHOLD), anyList()))
            .thenReturn(List.of(row(id, "A MERCHANT")));
        when(batch.categorise(any(), any(), anyList())).thenReturn(outcome(Map.of(), 25, 7));
        when(writeBack.apply(any(), any())).thenReturn(0);

        assertThatCode(() -> listener().onTransactionsIngested(ingested(List.of(id))))
            .doesNotThrowAnyException();

        verify(writeBack).apply(any(), eq(Map.of()));
    }

    /**
     * The annotation contract, asserted through the object that acts on it rather than through
     * {@code getAnnotation} alone.
     *
     * <p>{@link AnnotationTransactionAttributeSource} is the class Spring Boot puts behind every
     * {@code @Transactional} proxy: whatever it returns for a method is exactly what the running
     * container would do with it. Null here means the listener runs with nothing held while the
     * batch of model round-trips happens, which is the hazard §2 names; {@code REQUIRES_NEW} for
     * the write-back means the column is written in its own transaction, because after the commit
     * that published the event there is none left to join. {@code CategorisationSeamIT} proves the
     * same two facts against the container's own instance and under real advice; this case is the
     * cheap copy that fails in seconds.
     */
    @Test
    @DisplayName("AFTER_COMMIT with no @Transactional on the listener, REQUIRES_NEW on the "
        + "write-back — the hazard control")
    void theAnnotationContractIsTheHazardControl() throws Exception {
        AnnotationTransactionAttributeSource source = new AnnotationTransactionAttributeSource();

        Method onIngested = CategorisationListener.class.getMethod("onTransactionsIngested",
            TransactionsIngestedEvent.class);
        TransactionalEventListener phase =
            onIngested.getAnnotation(TransactionalEventListener.class);
        assertThat(phase).as("a plain @EventListener would act on a rollback").isNotNull();
        assertThat(phase.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(source.getTransactionAttribute(onIngested, CategorisationListener.class))
            .as("adding @Transactional here pins one pooled connection for every AI round-trip "
                + "in the batch — step 17's hazard, and the reason the write lives elsewhere")
            .isNull();

        Method apply = CategorisationWriteBack.class.getMethod("apply",
            TransactionsIngestedEvent.class, Map.class);
        TransactionAttribute applyAttribute = source.getTransactionAttribute(apply,
            CategorisationWriteBack.class);
        assertThat(applyAttribute).as("the column has to be written inside a transaction")
            .isNotNull();
        assertThat(applyAttribute.getPropagationBehavior())
            .as("a plain @Transactional would silently open none after the commit")
            .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
