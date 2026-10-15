package finance.trove.budgets.envelope;

import java.time.YearMonth;
import java.util.Objects;
import java.util.UUID;

/**
 * One (category, month) pair whose envelope needs recomputing — the unit of work
 * {@link EnvelopeSpendService#recalculateAll} takes.
 *
 * <p>It is a record so that a batch of them deduplicates by value in a {@code Set}: a sync that
 * ingests two hundred grocery transactions in one month produces two hundred identical pairs and
 * exactly one recomputation, which matters because the aggregate is a rewrite and the two hundredth
 * pass would compute the same number as the first.
 *
 * @param categoryId the category whose envelope moves; never {@code null}
 * @param month      the month the transaction falls in
 */
public record CategoryMonth(UUID categoryId, YearMonth month) {

    public CategoryMonth {
        Objects.requireNonNull(categoryId, "categoryId cannot be null");
        Objects.requireNonNull(month, "month cannot be null");
    }
}
