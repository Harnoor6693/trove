package finance.trove.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The defaults this step ships without touching {@code application.yml} — which it may not, since
 * three steps of this wave run in parallel worktrees and none of them owns that file.
 */
class NotificationPropertiesTest {

    @Test
    @DisplayName("every value has an inline default, so no yml entry is needed to boot")
    void everyValueDefaults() {
        NotificationProperties properties =
            new NotificationProperties(null, null, null, null, null, null);

        assertThat(properties.smtpEnabled())
            .as("SMTP off by default: the EMAIL channel bean does not exist until an operator "
                + "configures spring.mail.*, and a channel that silently drops everything is "
                + "finding D3")
            .isFalse();
        assertThat(properties.fromAddress()).isEqualTo("trove@localhost");
        assertThat(properties.fromName()).isEqualTo("Trove");
        assertThat(properties.digestPageSize()).isEqualTo(200);
        assertThat(properties.webhookBatchSize()).isEqualTo(20);
        assertThat(properties.maxWebhooksPerUser()).isEqualTo(20);
    }

    @Test
    @DisplayName("a blank sender falls back rather than producing an unsendable From header")
    void blankSendersFallBack() {
        NotificationProperties properties =
            new NotificationProperties(true, "  ", "", 10, 5, 3);

        assertThat(properties.fromAddress()).isEqualTo("trove@localhost");
        assertThat(properties.fromName()).isEqualTo("Trove");
        assertThat(properties.smtpEnabled()).isTrue();
    }

    @Test
    @DisplayName("a non-positive size fails the context refresh rather than a Monday at 08:00")
    void impossibleSizesFailAtStartup() {
        assertThatThrownBy(() -> new NotificationProperties(null, null, null, 0, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("digest-page-size");
        assertThatThrownBy(() -> new NotificationProperties(null, null, null, null, -1, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("webhook-batch-size");
        assertThatThrownBy(() -> new NotificationProperties(null, null, null, null, null, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("max-webhooks-per-user");
    }

    @Test
    @DisplayName("the property name the EMAIL channel is gated on is the one bound here")
    void theGatingPropertyNameMatchesThePrefix() {
        // EmailChannel's @ConditionalOnProperty takes a literal string; if it and this record's
        // prefix ever disagreed, the channel would be permanently absent and nothing would say so.
        assertThat(NotificationProperties.SMTP_ENABLED_PROPERTY)
            .isEqualTo("trove.notifications.smtp-enabled");
        assertThat(NotificationProperties.class
            .getAnnotation(org.springframework.boot.context.properties
                .ConfigurationProperties.class).value())
            .isEqualTo("trove.notifications");
    }
}
