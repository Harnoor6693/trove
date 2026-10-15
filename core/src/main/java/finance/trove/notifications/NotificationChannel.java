package finance.trove.notifications;

/**
 * How a notification is delivered ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.8, §6 D3).
 *
 * <p>Two constants, and the absence of a third is the point: the source had {@code PUSH_APNS} and
 * {@code PUSH_FCM}, and an originally drafted {@code ExpoPushChannel} was to replace them. All
 * three are cut by the owner's decision of 2026-09-03 — no push until a direct APNs/FCM path
 * exists, and no Expo relay — so there is no push channel, no device token column and no device
 * registration route anywhere in this design.
 *
 * <p>Both names are in schema V32's {@code channel} CHECK even though {@link #EMAIL} ships
 * disabled: widening a CHECK later costs a migration, and keeping it now costs nothing.
 */
public enum NotificationChannel {

    /**
     * The member's inbox. Always attempted, whatever the preferences say, because the row is the
     * durable record of what this server told them — finding D3's second half was five read
     * endpoints over a table nothing ever wrote to.
     */
    IN_APP,

    /**
     * SMTP. Implemented and unit-tested, but the channel bean only exists when
     * {@code trove.notifications.smtp-enabled} is true, which no shipped configuration sets.
     */
    EMAIL
}
