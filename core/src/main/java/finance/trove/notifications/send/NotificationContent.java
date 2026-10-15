package finance.trove.notifications.send;

import java.util.Map;
import java.util.Objects;

/**
 * What a producer wants said, and the key that decides whether it has already been said
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.8, §6 D3).
 *
 * <h2>Deduplication is a key, not a timer</h2>
 * {@link #dedupKey()} carries its own window inside it — an envelope and a month, a subscription
 * and its new amount, an account and the recipient's own local date, an ISO week — and V32's
 * partial unique index over {@code (user_id, type, dedup_key)} is what enforces it. Two producers
 * that really can run at the same time therefore cannot both send, because the second INSERT
 * conflicts. The alternative shape, "have we sent one of these in the last N hours?", is a
 * check-then-act across two transactions and is not a defence at all: both listeners read "no" and
 * both send.
 *
 * <p>A {@code null} key means this notification is deliberately not deduplicated, and V32's index
 * is partial (rather than {@code NULLS NOT DISTINCT}) precisely so that those rows do not all
 * collapse into one per member per type.
 *
 * @param subject      the email subject and the inbox title; at most 300 characters after trimming
 * @param body         plain text, never HTML — it is rendered by the web bell and the mobile inbox
 * @param actionUrl    a relative in-app path, or null
 * @param dedupKey     the type's own key, or null for a notification that is not deduplicated
 * @param templateName the Thymeleaf template under {@code templates/email/}, or null for a plain
 *                     text email. Only the email channel reads it
 * @param templateModel values for that template; never null, possibly empty
 */
public record NotificationContent(String subject, String body, String actionUrl, String dedupKey,
                                  String templateName, Map<String, Object> templateModel) {

    /** V33's {@code title} column, and the longest inbox title this server will write. */
    public static final int MAX_TITLE_LENGTH = 300;

    /** V32's {@code dedup_key} column. A key longer than this is a producer bug, not a value. */
    public static final int MAX_DEDUP_KEY_LENGTH = 200;

    public NotificationContent {
        Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(body, "body cannot be null");
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject cannot be blank");
        }
        if (subject.length() > MAX_TITLE_LENGTH) {
            subject = subject.substring(0, MAX_TITLE_LENGTH);
        }
        if (dedupKey != null && dedupKey.length() > MAX_DEDUP_KEY_LENGTH) {
            throw new IllegalArgumentException("a dedup key longer than " + MAX_DEDUP_KEY_LENGTH
                + " characters would be silently truncated by the unique index it exists to feed; "
                + "got " + dedupKey.length());
        }
        templateModel = templateModel == null ? Map.of() : Map.copyOf(templateModel);
    }

    /** A plain notification with no email template and no deep link. */
    public static NotificationContent of(String subject, String body, String dedupKey) {
        return new NotificationContent(subject, body, null, dedupKey, null, Map.of());
    }

    /** A notification with a deep link into the app. */
    public static NotificationContent of(String subject, String body, String actionUrl,
                                         String dedupKey) {
        return new NotificationContent(subject, body, actionUrl, dedupKey, null, Map.of());
    }
}
