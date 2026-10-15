package finance.trove.notifications.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.NotificationProperties;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.preference.NotificationPreference;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;

/**
 * The email channel, against a mocked {@code JavaMailSender}.
 *
 * <p>The channel ships DISABLED — its bean only exists when
 * {@code trove.notifications.smtp-enabled} is true, which no shipped configuration sets — so this
 * unit test is the only thing that exercises it. That is deliberate rather than a gap: the code is
 * built and proven, and turning it on needs an {@code application.yml} and a
 * {@code core/.env.example} edit that this collapse step may not make (three steps of this wave
 * share those files). The exact diff is in the step's merge notes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailChannelTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private TemplateEngine templates;

    private EmailChannel channel;
    private NotificationPreference preference;

    @BeforeEach
    void setUp() {
        channel = new EmailChannel(mailSender, templates, new NotificationProperties(
            true, "trove@example.test", "Trove", null, null, null));
        preference = NotificationPreference.defaultsFor(USER);
        preference.setEmailEnabled(true);
        preference.setEmailAddress("member@trove.test");
        when(mailSender.createMimeMessage())
            .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
    }

    @Test
    @DisplayName("the channel is EMAIL and needs both the opt-in and an address")
    void bothHalvesOfTheOptInAreRequired() {
        assertThat(channel.channel()).isEqualTo(NotificationChannel.EMAIL);
        assertThat(channel.enabledFor(preference)).isTrue();

        NotificationPreference noAddress = NotificationPreference.defaultsFor(USER);
        noAddress.setEmailEnabled(true);
        assertThat(channel.enabledFor(noAddress))
            .as("enabling the channel in a settings form before typing an address is accepted "
                + "and simply does not deliver, rather than being refused")
            .isFalse();

        NotificationPreference notOptedIn = NotificationPreference.defaultsFor(USER);
        notOptedIn.setEmailAddress("member@trove.test");
        assertThat(channel.enabledFor(notOptedIn)).isFalse();
    }

    @Test
    @DisplayName("a successful send is SENT with no error")
    void aSuccessfulSendIsSent() {
        DeliveryResult result = channel.send(USER, NotificationType.WEEKLY_DIGEST, preference,
            NotificationContent.of("Your week", "You spent CAD 10.00", "2026-W36"));

        assertThat(result.isSent()).isTrue();
        assertThat(result.error()).isNull();
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("an SMTP failure is FAILED with the real reason, not a swallowed log line")
    void anSmtpFailureIsReported() {
        // This is finding D3 at the channel. The source caught MailException, logged, and
        // returned normally — after which the dispatcher recorded the delivery as SENT.
        doThrow(new MailSendException("Connection refused to smtp.trove.test:25"))
            .when(mailSender).send(any(MimeMessage.class));

        DeliveryResult result = channel.send(USER, NotificationType.WEEKLY_DIGEST, preference,
            NotificationContent.of("Your week", "body", "2026-W36"));

        assertThat(result.isSent()).isFalse();
        assertThat(result.error()).contains("Connection refused to smtp.trove.test:25");
    }

    @Test
    @DisplayName("an allowlisted template is rendered; the body is not")
    void anAllowlistedTemplateIsRendered() {
        when(templates.process(any(String.class), any())).thenReturn("<html>digest</html>");
        ArgumentCaptor<String> template = ArgumentCaptor.forClass(String.class);

        channel.send(USER, NotificationType.WEEKLY_DIGEST, preference,
            new NotificationContent("Your week", "plain", null, "2026-W36", "weekly-digest",
                Map.of("weekStart", "2026-08-31")));

        verify(templates).process(template.capture(), any());
        assertThat(template.getValue()).isEqualTo("email/weekly-digest");
    }

    @Test
    @DisplayName("a template that is not on the allowlist never reaches Thymeleaf")
    void anUnknownTemplateIsRefused() {
        // A template name reaching TemplateEngine.process is a server-side template injection
        // primitive the day it becomes reachable from a request. It costs a set lookup to keep
        // that impossible.
        DeliveryResult result = channel.send(USER, NotificationType.BUDGET_THRESHOLD, preference,
            new NotificationContent("subject", "body", null, "key", "../../etc/passwd",
                Map.of()));

        assertThat(result.isSent()).isTrue();
        verify(templates, never()).process(any(String.class), any());
    }

    @Test
    @DisplayName("the plain-text fallback escapes the body")
    void theFallbackEscapesTheBody() {
        // The body carries merchant names, which arrive from a bank feed or an uploaded CSV, so
        // an unescaped one would be markup in every recipient's mail client. Asserted on the
        // rendered string rather than on the assembled MIME part: the escaping is the claim, and
        // walking a multipart to find it would test JavaMail instead.
        String rendered = channel.render(NotificationContent.of("subject",
            "<script>alert(1)</script> rose to CAD 9.99", "key"));

        assertThat(rendered).doesNotContain("<script>").contains("&lt;script&gt;")
            .contains("CAD 9.99");
    }
}
