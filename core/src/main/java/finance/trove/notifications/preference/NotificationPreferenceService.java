package finance.trove.notifications.preference;

import finance.trove.notifications.preference.dto.NotificationPreferencesUpdateRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes one member's notification settings (§2.8, §4.9).
 *
 * <p><strong>The row is created on first access, and it is PERSISTED rather than returned
 * transient.</strong> The source did the same and it is kept because a persisted row is what a
 * settings screen writes into and what an administrator can inspect.
 *
 * <p><strong>Nothing creates it at registration.</strong> {@code AuthService.register} saves the
 * {@code users} row and the credential and calls nothing here, and neither does the first-run
 * bootstrap — so between registering and the first settings read or the first notification of any
 * kind, a member genuinely has no row. That is safe, and it is safe for exactly one reason: no
 * read of this table may treat a missing row as "not interested". A missing row means the shipped
 * defaults of V31, and every query here honours that —
 * {@link NotificationPreferenceRepository#findDigestRecipients} enumerates from {@code users} with
 * a LEFT join, and {@link NotificationPreferenceRepository#findThresholdPercentsForHousehold}'s
 * caller falls back to the shipped default. An earlier version of this javadoc claimed
 * registration created the row ("see LocalAccountService") and the digest's recipient query was
 * an INNER join that trusted the claim; the claim was false, and every member who never opened the
 * settings screen was silently excluded from the digest.
 */
@Service
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository preferences;

    public NotificationPreferenceService(NotificationPreferenceRepository preferences) {
        this.preferences = preferences;
    }

    /**
     * This member's settings, creating the shipped defaults on first access.
     *
     * <p>Not {@code readOnly}: the create half writes.
     *
     * <p>The create is an {@code INSERT ... ON CONFLICT DO NOTHING} followed by a re-read rather
     * than a {@code save}, so that two callers reaching a member who has never had a row cannot
     * both insert. The loser reads the winner's row instead of taking a primary-key violation that
     * would roll back its caller's whole transaction — see
     * {@link NotificationPreferenceRepository#insertDefaults}.
     */
    @Transactional
    public NotificationPreference getOrCreate(UUID userId) {
        return preferences.findByUserId(userId).orElseGet(() -> {
            preferences.insertDefaults(userId);
            return preferences.findByUserId(userId).orElseThrow(() -> new IllegalStateException(
                "notification_preference for " + userId + " is absent immediately after an "
                    + "INSERT ... ON CONFLICT DO NOTHING, which can only mean the users row is "
                    + "gone"));
        });
    }

    /**
     * Applies the fields the caller actually sent and leaves the rest exactly as they were.
     *
     * <p>{@code emailAddress} is the one field where an explicit {@code null} differs from an
     * omission: it clears the stored address. Everything else treats null as "not mentioned" —
     * see {@link NotificationPreferencesUpdateRequest} for why that distinction needs a bean.
     *
     * <p>Turning {@code emailEnabled} on with no address is accepted and does nothing: the email
     * channel skips a member with no address, and refusing the write would make the two fields
     * order-dependent in a settings form that submits them together.
     */
    @Transactional
    public NotificationPreference update(UUID userId,
                                         NotificationPreferencesUpdateRequest request) {
        NotificationPreference preference = getOrCreate(userId);
        if (request.getInAppEnabled() != null) {
            preference.setInAppEnabled(request.getInAppEnabled());
        }
        if (request.getEmailEnabled() != null) {
            preference.setEmailEnabled(request.getEmailEnabled());
        }
        if (request.isEmailAddressPresent()) {
            String address = request.getEmailAddress();
            preference.setEmailAddress(address == null || address.isBlank() ? null : address);
        }
        if (request.getWeeklyDigestEnabled() != null) {
            preference.setWeeklyDigestEnabled(request.getWeeklyDigestEnabled());
        }
        if (request.getSubscriptionAlertEnabled() != null) {
            preference.setSubscriptionAlertEnabled(request.getSubscriptionAlertEnabled());
        }
        if (request.getSyncFailureAlertEnabled() != null) {
            preference.setSyncFailureAlertEnabled(request.getSyncFailureAlertEnabled());
        }
        if (request.getBudgetAlertThresholdPercent() != null) {
            preference.setBudgetAlertThresholdPercent(
                request.getBudgetAlertThresholdPercent().shortValue());
        }
        return preferences.save(preference);
    }
}
