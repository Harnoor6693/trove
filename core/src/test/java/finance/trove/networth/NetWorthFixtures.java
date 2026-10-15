package finance.trove.networth;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.users.User;
import finance.trove.users.UserRole;
import finance.trove.transactions.TransactionFixtures;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Builds {@link User} rows with a seeded id, for the unit tests in this package that need a
 * specific household administrator to resolve a timezone through.
 *
 * <p>Reuses {@link TransactionFixtures#withId} rather than duplicating its reflection — the id is
 * generated at persist time on every entity in this application, and a mocked repository never
 * assigns one.
 */
final class NetWorthFixtures {

    private NetWorthFixtures() {
    }

    static User member(UUID id, UUID householdId, UserRole role, ZoneId timezone) {
        User user = new User(householdId, role, id + "@example.test", "Test Member",
            Jurisdiction.CA, CurrencyCode.CAD, timezone);
        return TransactionFixtures.withId(user, id);
    }

    static User member(UUID id, UUID householdId, UserRole role, ZoneId timezone,
                       CurrencyCode currency) {
        User user = new User(householdId, role, id + "@example.test", "Test Member",
            currency == CurrencyCode.USD ? Jurisdiction.US : Jurisdiction.CA, currency, timezone);
        return TransactionFixtures.withId(user, id);
    }
}
