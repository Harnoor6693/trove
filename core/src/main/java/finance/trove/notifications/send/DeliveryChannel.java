package finance.trove.notifications.send;

import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.preference.NotificationPreference;
import java.util.UUID;

/**
 * One way of delivering a notification ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.8, §6 D3).
 *
 * <p><strong>{@link #send} returns rather than throws, and that is the whole design.</strong> A
 * channel that cannot report failure is a channel whose delivery log is decoration — see
 * {@link DeliveryResult} for the defect this replaced. An implementation that throws is not wrong,
 * it is simply less informative: {@code NotificationService} converts a thrown exception into a
 * {@code FAILED} result with the exception's message, so the log row is honest either way.
 *
 * <p>There are exactly two implementations and there will not be a third while the owner's
 * decision of 2026-09-03 stands: no push notifications until a direct APNs/FCM path exists, and no
 * Expo relay. {@code ApnsChannel} and {@code FcmChannel} are deleted rather than moved.
 */
public interface DeliveryChannel {

    /** Which channel this is; the value written to {@code notification_log.channel}. */
    NotificationChannel channel();

    /**
     * Whether this member has this channel switched on.
     *
     * <p>{@code IN_APP} answers true unconditionally: its preference governs whether a
     * notification ANNOUNCES itself, not whether the record is kept.
     */
    boolean enabledFor(NotificationPreference preference);

    /**
     * Delivers, and says what happened.
     *
     * @param userId     the recipient
     * @param type       what the notification is about
     * @param preference the recipient's settings, already loaded
     * @param content    what to say
     * @return never null; a {@code FAILED} result always carries a reason
     */
    DeliveryResult send(UUID userId, NotificationType type, NotificationPreference preference,
                        NotificationContent content);
}
