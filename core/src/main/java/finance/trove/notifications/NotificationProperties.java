package finance.trove.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything the notification area can be tuned by ({@code docs/recovery/06-COLLAPSE-PLAN.md}
 * §2.8, §1.6).
 *
 * <p><strong>Every value carries its own default here and nothing is added to
 * {@code application.yml}.</strong> Collapse steps 11, 15 and 17 run in parallel worktrees and no
 * step of this wave owns that file, so a property added to it is a merge conflict at best and a
 * silently lost edit at worst. A record bound by {@code @ConfigurationPropertiesScan} with
 * defaults in its canonical constructor needs no yml entry to work and picks one up the moment an
 * operator adds it — the shape {@code PurgeJobs.reloadRateLimits} uses for
 * {@code trove.rate-limit.reload-interval}. The keys are named in this step's merge notes so that
 * they can be promoted into the shipped configuration with the SMTP settings.
 *
 * <p>A record rather than a class because {@code ArchitectureTest} requires it: the canonical
 * constructor is where a bad value fails the context refresh instead of surfacing at 08:00 on a
 * Monday nobody is watching.
 *
 * @param smtpEnabled       whether the {@code EMAIL} channel bean exists at all. False on every
 *                          shipped configuration: without {@code spring.mail.host} there is no
 *                          {@code JavaMailSender} to inject, and a channel that silently drops
 *                          everything is exactly the defect D3 is about
 * @param fromAddress       the envelope sender for notification email
 * @param fromName          the display name shown beside it
 * @param digestPageSize    how many digest recipients are read per page. The job holds a page in
 *                          memory and commits nothing across pages, so this bounds the job's heap
 *                          rather than its correctness
 * @param webhookBatchSize  how many due deliveries one retry pass claims. Small on purpose: the
 *                          pass has 29 seconds of lock and each delivery may take 20
 * @param maxWebhooksPerUser how many subscriptions one member may hold. A cap rather than none,
 *                          because every subscription is an outbound connection this server makes
 *                          on that member's behalf
 */
@ConfigurationProperties("trove.notifications")
public record NotificationProperties(Boolean smtpEnabled, String fromAddress, String fromName,
                                     Integer digestPageSize, Integer webhookBatchSize,
                                     Integer maxWebhooksPerUser) {

    /** The property that gates the {@code EMAIL} channel bean, spelled out for the annotation. */
    public static final String SMTP_ENABLED_PROPERTY = "trove.notifications.smtp-enabled";

    public NotificationProperties {
        smtpEnabled = smtpEnabled != null && smtpEnabled;
        fromAddress = blankToDefault(fromAddress, "trove@localhost");
        fromName = blankToDefault(fromName, "Trove");
        digestPageSize = digestPageSize == null ? 200 : digestPageSize;
        webhookBatchSize = webhookBatchSize == null ? 20 : webhookBatchSize;
        maxWebhooksPerUser = maxWebhooksPerUser == null ? 20 : maxWebhooksPerUser;

        requirePositive(digestPageSize, "digest-page-size");
        requirePositive(webhookBatchSize, "webhook-batch-size");
        requirePositive(maxWebhooksPerUser, "max-webhooks-per-user");
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void requirePositive(int value, String key) {
        if (value < 1) {
            throw new IllegalArgumentException(
                "trove.notifications." + key + " must be at least 1, got " + value);
        }
    }
}
