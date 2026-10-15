package finance.trove.notifications.digest;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One currency's worth of one member's week.
 *
 * <p><strong>Per currency, never blended.</strong> There is no exchange rate anywhere in this
 * system (owner decision 3: CA and US are both first class), so a household that holds a CAD
 * account and a USD one gets two lines rather than one converted total — the same rule the
 * dashboard and net worth already follow. The source hardcoded one currency code into the digest
 * template's variables (WeeklyDigestScheduler.java:101, finding F1), which was wrong for every US
 * household and silently wrong for a mixed one.
 *
 * @param currency         the currency these figures are in
 * @param spent            debits less refunds, transfer legs already excluded by the query
 * @param income           credits
 * @param transactionCount how many rows contributed
 * @param topCategoryId    the category with the most spending, or null when nothing was spent
 * @param topCategorySpend that category's spend; zero when there is no top category
 */
public record WeeklyDigestSummary(CurrencyCode currency, BigDecimal spent, BigDecimal income,
                                  int transactionCount, java.util.UUID topCategoryId,
                                  BigDecimal topCategorySpend) {

    public WeeklyDigestSummary {
        Objects.requireNonNull(currency, "currency cannot be null");
        spent = currency.normalise(Objects.requireNonNull(spent, "spent cannot be null"));
        income = currency.normalise(Objects.requireNonNull(income, "income cannot be null"));
        topCategorySpend = currency.normalise(
            topCategorySpend == null ? BigDecimal.ZERO : topCategorySpend);
        if (transactionCount < 0) {
            throw new IllegalArgumentException("transactionCount cannot be negative");
        }
    }

    /** Income less spending. Negative when the week cost more than it brought in. */
    public BigDecimal net() {
        return currency.normalise(income.subtract(spent));
    }

    /** Whether this currency saw any movement at all. */
    public boolean isEmpty() {
        return transactionCount == 0;
    }
}
