/**
 * Notifications: the member's inbox, the preferences that govern it, the delivery channels, the
 * outbound webhooks and the weekly digest
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §1.3, §2.8, §4.9, §6 D3).
 *
 * <p><strong>Everything here is scoped by user, not by household, and that is the opposite of
 * every other domain package in this application.</strong> The owner's tenancy decision is one
 * shared ledger per install (§1.5), so {@code transaction}, {@code envelope} and the rest are
 * reached through {@code household_id} and a household mate sees them all. A notification is not
 * ledger data: it is addressed to one person, its delivery preferences are that person's, and a
 * webhook points at a URL that person controls and is signed with a secret only they hold. The
 * four tables this package owns therefore carry {@code user_id} and no {@code household_id}, the
 * repositories extend {@link finance.trove.common.repository.UserOwnedRepository} or
 * {@link finance.trove.common.repository.UserKeyedRepository}, and every by-id route answers 404
 * to a household mate. Getting that backwards would leak one member's inbox to the household.
 *
 * <p><strong>There is no push channel</strong>, and there is not one because of a decision rather
 * than an omission (owner, 2026-09-03): no push notifications until a direct APNs/FCM path
 * exists, and no Expo relay. The pre-collapse service's {@code ApnsChannel}, {@code FcmChannel},
 * {@code push_enabled}, {@code apns_token}, {@code fcm_token} and the two device-registration
 * routes are deleted rather than moved. Mobile polls
 * {@code GET /v1/notifications/unread-count} and raises a client-local notification.
 *
 * <p>Two channels exist: {@code IN_APP}, which is always attempted and is the durable record, and
 * {@code EMAIL}, which is implemented and unit-tested but gated off until an operator configures
 * SMTP.
 */
package finance.trove.notifications;
