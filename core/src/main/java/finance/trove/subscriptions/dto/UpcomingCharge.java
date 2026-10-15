package finance.trove.subscriptions.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * The subscriptions whose next charge falls on one date, so a client can render a calendar strip
 * without regrouping the list itself.
 *
 * @param date          a LOCAL date, resolved in the caller's own timezone
 * @param subscriptions the subscriptions due that day, ordered by merchant name
 * @param totals        that day's total per currency present
 */
public record UpcomingCharge(LocalDate date, List<SubscriptionResponse> subscriptions,
                             List<SubscriptionCurrencyAmount> totals) {

    public UpcomingCharge {
        subscriptions = List.copyOf(subscriptions);
        totals = List.copyOf(totals);
    }
}
