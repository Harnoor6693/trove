package finance.trove.transactions;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.plugin.TransactionType;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Builds {@link Transaction} rows as they would look once stored, for the unit tests that reason
 * about rows rather than about SQL.
 *
 * <p>The id is seeded by reflection because it is generated at persist time and every rule these
 * tests cover — one group per leg, one candidate per incoming row — is about telling one stored
 * row from another. A mocked repository never assigns one, so without this the rules would all be
 * comparing {@code null} to {@code null}, which is the shape of a test that passes for the wrong
 * reason.
 */
public final class TransactionFixtures {

    private TransactionFixtures() {
    }

    /** A stored, posted DEBIT. */
    public static Transaction stored(UUID householdId, UUID accountId, String amount,
                                     LocalDate date, TransactionType type) {
        return stored(householdId, accountId, amount, date, type, CurrencyCode.CAD);
    }

    /** A stored, posted transaction in a chosen currency. */
    public static Transaction stored(UUID householdId, UUID accountId, String amount,
                                     LocalDate date, TransactionType type,
                                     CurrencyCode currency) {
        return stored(householdId, accountId, amount, date, type, currency, "CARD PAYMENT");
    }

    /** A stored transaction with a chosen description (the pairer reads it for a transfer marker). */
    public static Transaction stored(UUID householdId, UUID accountId, String amount,
                                     LocalDate date, TransactionType type,
                                     CurrencyCode currency, String description) {
        Transaction transaction = new Transaction(householdId, accountId, UUID.randomUUID(), null,
            new BigDecimal(amount), currency, type, description, "A Merchant", date,
            Instant.now(), false, false, Instant.now());
        withId(transaction, UUID.randomUUID());
        return transaction;
    }

    /** Seeds an id on any entity whose primary key field is named {@code id}. */
    public static <T> T withId(T entity, UUID id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
            return entity;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot seed an id on " + entity.getClass(), e);
        }
    }
}
