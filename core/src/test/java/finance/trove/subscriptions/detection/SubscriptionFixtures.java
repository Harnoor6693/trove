package finance.trove.subscriptions.detection;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import finance.trove.transactions.Transaction;
import finance.trove.transactions.TransactionFixtures;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Stored-looking DEBIT rows for the unit tests that reason about a merchant's charge history.
 *
 * <p>Ids are seeded through {@link TransactionFixtures#withId}, because
 * {@code subscription_transaction} is built from them and a fixture that left them null would
 * make the link assertions compare {@code null} to {@code null}.
 */
final class SubscriptionFixtures {

    static final UUID HOUSEHOLD = UUID.randomUUID();
    static final UUID ACCOUNT = UUID.randomUUID();

    private SubscriptionFixtures() {
    }

    /** One charge. */
    static Transaction charge(String merchant, String amount, LocalDate date,
                              CurrencyCode currency) {
        Transaction transaction = new Transaction(HOUSEHOLD, ACCOUNT, UUID.randomUUID(), null,
            new BigDecimal(amount), currency, TransactionType.DEBIT, merchant, merchant, date,
            Instant.now(), false, false, Instant.now());
        return TransactionFixtures.withId(transaction, UUID.randomUUID());
    }

    /** {@code count} charges of one amount, one cadence apart, ending on {@code lastSeen}. */
    static List<Transaction> series(String merchant, String amount, LocalDate lastSeen,
                                    BillingCycle cycle, int count, CurrencyCode currency) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate at = lastSeen;
        for (int i = 0; i < count; i++) {
            dates.add(at);
            at = at.minusDays(cycle.cycleDays());
        }
        List<Transaction> charges = new ArrayList<>();
        for (int i = dates.size() - 1; i >= 0; i--) {
            charges.add(charge(merchant, amount, dates.get(i), currency));
        }
        return charges;
    }

    /** Three monthly CAD charges of one amount, the smallest history a detection needs. */
    static List<Transaction> monthly(String merchant, String amount, LocalDate lastSeen) {
        return series(merchant, amount, lastSeen, BillingCycle.MONTHLY, 3, CurrencyCode.CAD);
    }
}
