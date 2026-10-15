package finance.trove.subscriptions.alert;

import finance.trove.common.repository.UserScopedRepository;
import java.util.List;
import java.util.UUID;

/**
 * Repository over {@link SubscriptionAlert} ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.7,
 * §9 risk 2).
 *
 * <p>Extends {@link UserScopedRepository}, so there is no {@code findById} to reach for: the
 * mark-read route resolves an alert through {@code findByIdAndHouseholdId} and an id from another
 * household is simply not found.
 */
public interface SubscriptionAlertRepository
    extends UserScopedRepository<SubscriptionAlert, UUID> {

    /**
     * The household's unread alerts, newest first — what {@code GET /v1/subscriptions/alerts}
     * returns.
     *
     * <p>Served by V30's partial index {@code subscription_alert_household_unread_idx}: on a
     * healthy install the unread rows are a small minority of an append-only table, so a partial
     * index keeps this read proportional to what it returns rather than to the table's history.
     */
    List<SubscriptionAlert> findAllByHouseholdIdAndReadFalseOrderByCreatedAtDesc(UUID householdId);
}
