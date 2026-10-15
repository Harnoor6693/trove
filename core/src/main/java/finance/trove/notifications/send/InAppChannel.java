package finance.trove.notifications.send;

import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.inapp.InAppNotificationService;
import finance.trove.notifications.preference.NotificationPreference;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The inbox channel: writes one {@code in_app_notification} row (V33).
 *
 * <p><strong>Always attempted, whatever the preferences say</strong>, because the row is the
 * durable record of what this server told a member. Finding D3's second half was five read
 * endpoints over a table with no writer; making the write conditional on a preference would put
 * the same hole back behind a checkbox.
 *
 * <p>What {@code inAppEnabled} governs is whether the notification ANNOUNCES itself. With it off
 * the row is written already read, so it never reaches the unread badge that mobile polls and the
 * web bell renders — "do not interrupt me" and "do not keep a record" are different requests, and
 * only the first is one a preference should be able to make.
 *
 * <p>This channel's {@link DeliveryResult} is always {@code SENT}, and that is honest rather than
 * a restatement of the D3 defect: the write happens in the caller's own transaction, so it either
 * succeeds or the whole notification — the claim row included — rolls back and no log row survives
 * to lie about it. The email channel is the one that can fail while the inbox row stands, and
 * {@code NotificationServiceIT} asserts exactly that pairing.
 */
@Component
public class InAppChannel implements DeliveryChannel {

    private final InAppNotificationService inbox;

    public InAppChannel(InAppNotificationService inbox) {
        this.inbox = inbox;
    }

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    /** Always true — see the class javadoc; the preference governs announcement, not recording. */
    @Override
    public boolean enabledFor(NotificationPreference preference) {
        return true;
    }

    @Override
    public DeliveryResult send(UUID userId, NotificationType type,
                               NotificationPreference preference, NotificationContent content) {

        inbox.record(userId, type, content.subject(), content.body(), content.actionUrl(),
            preference.isInAppEnabled());
        return DeliveryResult.sent();
    }
}
