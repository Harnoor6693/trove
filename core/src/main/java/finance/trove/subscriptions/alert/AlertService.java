package finance.trove.subscriptions.alert;

import finance.trove.common.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and acknowledging subscription alerts ({@code docs/recovery/06-COLLAPSE-PLAN.md} §4.8).
 *
 * <p>Alerts are written only by {@code SubscriptionDetectionService}; this service exposes the two
 * operations the controller serves. Ownership is 404-not-403 by construction — the mark-read path
 * resolves through {@link SubscriptionAlertRepository}'s household-scoped finder, so an id from
 * another household is simply not found (§9 risk 2).
 */
@Service
public class AlertService {

    private final SubscriptionAlertRepository alerts;

    public AlertService(SubscriptionAlertRepository alerts) {
        this.alerts = alerts;
    }

    /** The household's unread alerts, newest first. */
    @Transactional(readOnly = true)
    public List<SubscriptionAlert> listUnread(UUID householdId) {
        return alerts.findAllByHouseholdIdAndReadFalseOrderByCreatedAtDesc(householdId);
    }

    /**
     * Acknowledges one alert.
     *
     * <p>Idempotent: marking an already-read alert read again succeeds and returns the same row,
     * so a client that retries a request whose response it never saw does not get a 409 for a
     * write that already happened.
     */
    @Transactional
    public SubscriptionAlert markRead(UUID householdId, UUID alertId) {
        SubscriptionAlert alert = alerts.findByIdAndHouseholdId(alertId, householdId)
            .orElseThrow(() -> ResourceNotFoundException.of("Subscription alert", alertId));
        alert.markRead();
        return alerts.save(alert);
    }
}
