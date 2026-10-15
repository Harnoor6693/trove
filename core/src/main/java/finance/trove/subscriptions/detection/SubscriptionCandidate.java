package finance.trove.subscriptions.detection;

import finance.trove.common.domain.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What one scan concluded about one merchant, before anything is written
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §6 "D2 — subscription scan").
 *
 * <p>A value with no database in it, so the whole decision — cadence, price, projection, signal —
 * is testable without a container, and so the upsert has exactly one thing to write rather than a
 * pile of loose parameters.
 *
 * @param merchantName   the normalised merchant, from {@code MerchantNormaliser}
 * @param currency       the currency these charges were made in; never defaulted
 * @param amount         the current price, at {@code currency}'s scale
 * @param billingCycle   the cadence the gaps were classified into
 * @param nextChargeDate {@code lastSeenDate} advanced by exactly one cycle
 * @param overdue        whether that date is further in the past than the cadence tolerates —
 *                       the charge was expected and has not been observed
 * @param firstSeenDate  the earliest charge in the window
 * @param lastSeenDate   the most recent charge
 * @param categoryId     the category of the most recent charge that carries one, or {@code null}
 * @param confidence     the score {@link ConfidenceCalculator} gave, for logging and tests
 * @param transactionIds the charges this conclusion was drawn from, for
 *                       {@code subscription_transaction}
 */
public record SubscriptionCandidate(String merchantName, CurrencyCode currency, BigDecimal amount,
                                    BillingCycle billingCycle, LocalDate nextChargeDate,
                                    boolean overdue, LocalDate firstSeenDate,
                                    LocalDate lastSeenDate, UUID categoryId,
                                    BigDecimal confidence, List<UUID> transactionIds) {

    public SubscriptionCandidate {
        transactionIds = List.copyOf(transactionIds);
    }

    /**
     * The signal this candidate implies on its own — before the upsert compares it with what is
     * already stored.
     *
     * <p>{@code RED} beats everything: a charge that has not arrived is more urgent than a price
     * that moved. The upsert raises {@code GREEN} to {@code AMBER} when it finds the row's price
     * has changed, which is a fact only the stored row knows.
     */
    public String baseSignal() {
        return overdue ? "RED" : "GREEN";
    }
}
