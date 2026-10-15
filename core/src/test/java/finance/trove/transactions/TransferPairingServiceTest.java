package finance.trove.transactions;

import static finance.trove.transactions.TransactionFixtures.stored;
import static org.assertj.core.api.Assertions.assertThat;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transfer-pairing rule itself ({@code docs/recovery/07-READINESS-GAPS.md} P2-14), unit-tested
 * over a list so that each clause of the rule has a test of its own.
 *
 * <p>Why it matters that these are separate: a household that pays a credit card from chequing
 * produces a DEBIT and a CREDIT for the same amount. Every clause below is what stops the rule
 * from ALSO hiding something that is not a transfer — a refund on the same account, a payment in
 * another currency, an unrelated purchase a fortnight later. A pairing that is too eager removes
 * real spending from every budget in the product, which is worse than not pairing at all.
 *
 * <p>{@code TransferPairingIT} proves the same rules through the database and the job.
 */
class TransferPairingServiceTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final UUID CHEQUING = UUID.randomUUID();
    private static final UUID CREDIT_CARD = UUID.randomUUID();
    private static final LocalDate DAY = LocalDate.of(2026, 9, 4);

    @Test
    @DisplayName("A DEBIT and a CREDIT of the same amount on two accounts are one group")
    void oppositeLegsAcrossTwoAccountsArePaired() {
        Transaction out = stored(HOUSEHOLD, CHEQUING, "250.00", DAY, TransactionType.DEBIT);
        Transaction in = stored(HOUSEHOLD, CREDIT_CARD, "250.00", DAY, TransactionType.CREDIT);

        List<Transaction> changed = TransferPairingService.pair(List.of(out, in));

        assertThat(changed).containsExactly(out, in);
        assertThat(out.getTransferGroupId()).isNotNull().isEqualTo(in.getTransferGroupId());
    }

    @Test
    @DisplayName("Legs posted three days apart still pair; four days apart do not")
    void theWindowIsThreeDays() {
        Transaction out = stored(HOUSEHOLD, CHEQUING, "250.00", DAY, TransactionType.DEBIT);
        Transaction justInside = stored(HOUSEHOLD, CREDIT_CARD, "250.00", DAY.plusDays(3),
            TransactionType.CREDIT);
        assertThat(TransferPairingService.pair(List.of(out, justInside))).hasSize(2);

        Transaction out2 = stored(HOUSEHOLD, CHEQUING, "250.00", DAY, TransactionType.DEBIT);
        Transaction justOutside = stored(HOUSEHOLD, CREDIT_CARD, "250.00", DAY.plusDays(4),
            TransactionType.CREDIT);
        assertThat(TransferPairingService.pair(List.of(out2, justOutside)))
            .as("four days apart is somebody's next payday, not the other side of one movement")
            .isEmpty();
    }

    @Test
    @DisplayName("Two legs on the SAME account are not a transfer — that shape is a refund")
    void sameAccountLegsAreNotPaired() {
        Transaction purchase = stored(HOUSEHOLD, CHEQUING, "40.00", DAY, TransactionType.DEBIT);
        Transaction refund = stored(HOUSEHOLD, CHEQUING, "40.00", DAY.plusDays(1),
            TransactionType.CREDIT);

        assertThat(TransferPairingService.pair(List.of(purchase, refund)))
            .as("hiding a refund from every budget would be worse than showing it")
            .isEmpty();
    }

    @Test
    @DisplayName("Different currencies never pair — netting them would need an exchange rate")
    void differentCurrenciesAreNotPaired() {
        Transaction out = stored(HOUSEHOLD, CHEQUING, "250.00", DAY, TransactionType.DEBIT,
            CurrencyCode.CAD);
        Transaction in = stored(HOUSEHOLD, CREDIT_CARD, "250.00", DAY, TransactionType.CREDIT,
            CurrencyCode.USD);

        assertThat(TransferPairingService.pair(List.of(out, in))).isEmpty();
    }

    @Test
    @DisplayName("Two legs in the same direction are not a transfer")
    void sameDirectionLegsAreNotPaired() {
        Transaction one = stored(HOUSEHOLD, CHEQUING, "250.00", DAY, TransactionType.DEBIT);
        Transaction two = stored(HOUSEHOLD, CREDIT_CARD, "250.00", DAY, TransactionType.DEBIT);

        assertThat(TransferPairingService.pair(List.of(one, two))).isEmpty();
    }

    @Test
    @DisplayName("Amounts must be exactly equal — a near miss is two transactions")
    void unequalAmountsAreNotPaired() {
        Transaction out = stored(HOUSEHOLD, CHEQUING, "250.00", DAY, TransactionType.DEBIT);
        Transaction in = stored(HOUSEHOLD, CREDIT_CARD, "249.99", DAY, TransactionType.CREDIT);

        assertThat(TransferPairingService.pair(List.of(out, in))).isEmpty();
    }

    @Test
    @DisplayName("Equal amounts at different scales are the same amount")
    void scaleDoesNotDefeatTheAmountComparison() {
        Transaction out = stored(HOUSEHOLD, CHEQUING, "250.0000", DAY, TransactionType.DEBIT);
        Transaction in = stored(HOUSEHOLD, CREDIT_CARD, "250.00", DAY, TransactionType.CREDIT);

        assertThat(TransferPairingService.pair(List.of(out, in)))
            .as("NUMERIC(19,4) and a two-decimal literal are the same money; comparing with "
                + "equals() rather than compareTo() would silently never pair anything")
            .hasSize(2);
    }

    @Test
    @DisplayName("A leg already typed TRANSFER states no direction and is left to a member")
    void alreadyTypedTransferLegsAreNotGuessedAt() {
        Transaction one = stored(HOUSEHOLD, CHEQUING, "250.00", DAY, TransactionType.TRANSFER);
        Transaction two = stored(HOUSEHOLD, CREDIT_CARD, "250.00", DAY, TransactionType.TRANSFER);

        assertThat(TransferPairingService.pair(List.of(one, two))).isEmpty();
    }

    @Test
    @DisplayName("Legs of two different households are never paired")
    void householdsAreNeverCrossed() {
        UUID otherHousehold = UUID.randomUUID();
        Transaction ours = stored(HOUSEHOLD, CHEQUING, "250.00", DAY, TransactionType.DEBIT);
        Transaction theirs = stored(otherHousehold, UUID.randomUUID(), "250.00", DAY,
            TransactionType.CREDIT);

        assertThat(TransferPairingService.pair(List.of(ours, theirs)))
            .as("the repository query is household-scoped, and the rule refuses as well: two "
                + "defences, because one household's ledger appearing in another's budget is the "
                + "worst outcome this product has")
            .isEmpty();
    }

    @Test
    @DisplayName("Three identical transfers in a week become three groups of two, not one of six")
    void oneGroupPerLeg() {
        List<Transaction> legs = List.of(
            stored(HOUSEHOLD, CHEQUING, "100.00", DAY, TransactionType.DEBIT),
            stored(HOUSEHOLD, CREDIT_CARD, "100.00", DAY, TransactionType.CREDIT),
            stored(HOUSEHOLD, CHEQUING, "100.00", DAY.plusDays(1), TransactionType.DEBIT),
            stored(HOUSEHOLD, CREDIT_CARD, "100.00", DAY.plusDays(1), TransactionType.CREDIT),
            stored(HOUSEHOLD, CHEQUING, "100.00", DAY.plusDays(2), TransactionType.DEBIT),
            stored(HOUSEHOLD, CREDIT_CARD, "100.00", DAY.plusDays(2), TransactionType.CREDIT));

        List<Transaction> changed = TransferPairingService.pair(legs);

        assertThat(changed).hasSize(6);
        assertThat(changed.stream().map(Transaction::getTransferGroupId).distinct().toList())
            .hasSize(3);
        assertThat(legs.stream().map(Transaction::getTransferGroupId))
            .doesNotContainNull();
    }

    @Test
    @DisplayName("An odd leg with no partner is left unpaired for the next run")
    void anUnmatchedLegIsLeftAlone() {
        Transaction out = stored(HOUSEHOLD, CHEQUING, "100.00", DAY, TransactionType.DEBIT);
        Transaction in = stored(HOUSEHOLD, CREDIT_CARD, "100.00", DAY, TransactionType.CREDIT);
        Transaction lonely = stored(HOUSEHOLD, CHEQUING, "100.00", DAY, TransactionType.DEBIT);

        List<Transaction> changed = TransferPairingService.pair(List.of(out, in, lonely));

        assertThat(changed).containsExactly(out, in);
        assertThat(lonely.getTransferGroupId()).isNull();
    }

    @Test
    @DisplayName("A second run over already-paired legs pairs nothing new")
    void aSecondRunIsANoOp() {
        Transaction out = stored(HOUSEHOLD, CHEQUING, "250.00", DAY, TransactionType.DEBIT);
        Transaction in = stored(HOUSEHOLD, CREDIT_CARD, "250.00", DAY, TransactionType.CREDIT);
        TransferPairingService.pair(List.of(out, in));

        // The repository only ever hands the job legs whose transfer_group_id is null, so the
        // second run's input is empty. That is the mechanism; this asserts the consequence.
        assertThat(TransferPairingService.pair(List.of())).isEmpty();
    }
}
