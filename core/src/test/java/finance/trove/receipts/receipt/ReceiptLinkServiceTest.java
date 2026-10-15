package finance.trove.receipts.receipt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionQueryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * How the one writer of the receipt/transaction link reads the rows it is about to write.
 *
 * <p>Writing both halves in one transaction makes them <em>commit</em> together; it does not make
 * two concurrent callers take turns. At {@code READ COMMITTED} two household mates attaching two
 * different receipts to the same transaction would both read that transaction's
 * {@code receipt_id} as null, neither would clear the other's half, and the ledger row would end
 * up naming one receipt while both receipts named it — with nothing to detect it afterwards and no
 * {@code UNIQUE} constraint to catch it (V38's header says why a partial unique index is the wrong
 * instrument). The row lock is what orders them, so every path that writes a transaction's
 * {@code receipt_id} must read it with {@code SELECT ... FOR UPDATE}.
 *
 * <p>These tests pin exactly that: a plain read on any of those paths is the defect coming back.
 */
class ReceiptLinkServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();

    private ReceiptRepository receipts;
    private TransactionQueryService transactions;
    private ReceiptLinkService links;

    @BeforeEach
    void setUp() {
        receipts = mock(ReceiptRepository.class);
        transactions = mock(TransactionQueryService.class);
        links = new ReceiptLinkService(receipts, transactions);
        when(receipts.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactions.saveLinked(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Receipt receipt() {
        Receipt receipt = new Receipt(HOUSEHOLD, UUID.randomUUID(), ReceiptSource.UPLOAD, 100L);
        // A persisted row has one; @GeneratedValue does not fill it in outside a session, and the
        // link is written in terms of it.
        ReflectionTestUtils.setField(receipt, "id", UUID.randomUUID());
        return receipt;
    }

    private static Transaction transaction() {
        return new Transaction(HOUSEHOLD, UUID.randomUUID(), UUID.randomUUID(), null,
            new BigDecimal("10.00"), CurrencyCode.CAD, TransactionType.DEBIT, "Coffee", "Shop",
            LocalDate.of(2026, 6, 5), Instant.now(), false, true, Instant.now());
    }

    @Test
    @DisplayName("the target transaction is read FOR UPDATE, never with a plain read")
    void theTargetTransactionIsLocked() {
        Receipt receipt = receipt();
        UUID transactionId = UUID.randomUUID();
        Transaction target = transaction();
        when(transactions.findOneForUpdate(HOUSEHOLD, transactionId))
            .thenReturn(Optional.of(target));

        links.link(receipt, transactionId);

        verify(transactions).findOneForUpdate(HOUSEHOLD, transactionId);
        verify(transactions, never()).findOne(any(), any());

        // Both halves, and both of them from this one call.
        assertThat(receipt.getMatchedTransactionId()).isEqualTo(transactionId);
        assertThat(target.getReceiptId()).isEqualTo(receipt.getId());
        verify(receipts).save(receipt);
        verify(transactions).saveLinked(target);
    }

    @Test
    @DisplayName("the transaction a receipt is moving away from is locked too, since it is written")
    void thePreviousPartnerIsLockedBeforeItIsCleared() {
        Receipt receipt = receipt();
        UUID previousId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        Transaction previous = transaction();
        previous.setReceiptId(receipt.getId());
        when(transactions.findOneForUpdate(HOUSEHOLD, previousId))
            .thenReturn(Optional.of(previous));
        when(transactions.findOneForUpdate(HOUSEHOLD, newId))
            .thenReturn(Optional.of(transaction()));
        receipt.setMatchedTransactionId(previousId);

        links.link(receipt, newId);

        verify(transactions).findOneForUpdate(HOUSEHOLD, previousId);
        verify(transactions, never()).findOne(eq(HOUSEHOLD), eq(previousId));
        assertThat(previous.getReceiptId())
            .as("the old partner must be detached, not left pointing at a receipt that has moved")
            .isNull();
    }

    @Test
    @DisplayName("unlinking locks the transaction it detaches from as well")
    void unlinkLocksTheTransactionItDetachesFrom() {
        Receipt receipt = receipt();
        UUID previousId = UUID.randomUUID();
        Transaction previous = transaction();
        previous.setReceiptId(receipt.getId());
        receipt.setMatchedTransactionId(previousId);
        when(receipts.findByIdAndHouseholdId(any(), eq(HOUSEHOLD)))
            .thenReturn(Optional.of(receipt));
        when(transactions.findOneForUpdate(HOUSEHOLD, previousId))
            .thenReturn(Optional.of(previous));

        links.unlink(HOUSEHOLD, UUID.randomUUID());

        verify(transactions).findOneForUpdate(HOUSEHOLD, previousId);
        verify(transactions, never()).findOne(any(), any());
        assertThat(receipt.getMatchedTransactionId()).isNull();
        assertThat(previous.getReceiptId()).isNull();
    }
}
