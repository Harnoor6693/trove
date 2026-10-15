package finance.trove.notifications.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.NotificationSupport;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.inapp.InAppNotificationService;
import finance.trove.notifications.preference.NotificationPreference;
import finance.trove.notifications.preference.NotificationPreferenceService;
import finance.trove.notifications.preference.dto.NotificationPreferencesUpdateRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * §9 risk 13, against the real schema: <strong>a channel that fails writes {@code FAILED} with the
 * real error, and the in-app row is present either way.</strong>
 *
 * <p>This is the D3 regression gate. The pre-collapse dispatcher initialised
 * {@code status = "SENT"} before it attempted anything and only downgraded inside a {@code catch}
 * that none of its three channels could ever reach, so {@code notification_log.status} was
 * permanently {@code SENT} and {@code error_message} permanently null on every install. Both
 * assertions below fail against that shape.
 *
 * <p>The failing channel is a stub rather than a real SMTP stub, and that is deliberate: the
 * {@code EMAIL} channel bean does not exist in this context at all
 * ({@code trove.notifications.smtp-enabled} is false on every shipped configuration, and no
 * {@code spring.mail.host} is set), so pointing one at a dead port would test Spring's mail
 * autoconfiguration rather than this service. What risk 13 actually asks — "SMTP stub down means
 * status FAILED with the error" — is a claim about how the SERVICE records a channel's answer, and
 * the answer is what is stubbed here. {@code EmailChannelTest} covers the other half, that a real
 * {@code MailException} becomes exactly that answer.
 *
 * <p>The service is constructed by hand rather than autowired so that the stub can be in its
 * channel list. Every collaborator it takes IS the autowired one, and no second Spring context is
 * started — which the IT suite forbids.
 */
class NotificationServiceIT extends NotificationSupport {

    private static final String SMTP_ERROR = "Connection refused: smtp.trove.test:25";

    @Autowired
    private NotificationLogRepository logs;

    @Autowired
    private NotificationPreferenceService preferences;

    @Autowired
    private InAppNotificationService inbox;

    private Session member;

    @BeforeEach
    void signIn() {
        member = inviteAndRegister(loginBootstrapAdmin(), "delivery-status");
    }

    /** A channel that always fails, the way a mail server that is down does. */
    private static final class DeadChannel implements DeliveryChannel {

        @Override
        public NotificationChannel channel() {
            return NotificationChannel.EMAIL;
        }

        @Override
        public boolean enabledFor(NotificationPreference preference) {
            return preference.isEmailEnabled();
        }

        @Override
        public DeliveryResult send(UUID userId, NotificationType type,
                                   NotificationPreference preference,
                                   NotificationContent content) {
            return DeliveryResult.failed(SMTP_ERROR);
        }
    }

    private NotificationService serviceWith(DeliveryChannel... extra) {
        DeliveryChannel[] channels = new DeliveryChannel[extra.length + 1];
        channels[0] = new InAppChannel(inbox);
        System.arraycopy(extra, 0, channels, 1, extra.length);
        return new NotificationService(logs, preferences, List.of(channels),
            new SimpleMeterRegistry());
    }

    private void enableEmail() {
        NotificationPreferencesUpdateRequest request = new NotificationPreferencesUpdateRequest();
        request.setEmailEnabled(true);
        request.setEmailAddress("member@trove.test");
        preferences.update(member.userId(), request);
    }

    @Test
    @DisplayName("A channel that fails writes FAILED with a non-null reason")
    void aFailingChannelIsRecordedHonestly() {
        enableEmail();
        String key = UUID.randomUUID().toString();

        boolean written = serviceWith(new DeadChannel()).notify(member.userId(),
            NotificationType.WEEKLY_DIGEST,
            NotificationContent.of("Your week", "body", key));

        assertThat(written).isTrue();
        Map<String, Object> emailRow = jdbcTemplate.queryForMap(
            "SELECT status, error_message, sent_at FROM notification_log "
                + "WHERE user_id = ? AND channel = 'EMAIL' AND type = 'WEEKLY_DIGEST'",
            member.userId());

        assertThat(emailRow.get("status"))
            .as("the pre-collapse dispatcher wrote SENT here, always and for everybody")
            .isEqualTo("FAILED");
        assertThat(emailRow.get("error_message"))
            .as("and it wrote null here, always: a FAILED row with no reason is the same defect")
            .isEqualTo(SMTP_ERROR);
        assertThat(emailRow.get("sent_at")).isNull();
    }

    @Test
    @DisplayName("The in-app row is written even though the other channel failed")
    void theInboxRowSurvivesAChannelFailure() {
        // Finding D3's second half. The inbox is the durable record of what this server told a
        // member, so it must not depend on any channel succeeding.
        enableEmail();
        String key = UUID.randomUUID().toString();

        serviceWith(new DeadChannel()).notify(member.userId(), NotificationType.SYNC_FAILED,
            NotificationContent.of("An account could not be synced", "body", key));

        assertThat(inbox(member))
            .as("the member can still find out what happened, from the one channel that is "
                + "never switched off")
            .anyMatch(row -> "An account could not be synced".equals(row.path("title").asString()));
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM notification_log WHERE user_id = ? AND channel = 'IN_APP' "
                + "AND dedup_key = ?", String.class, member.userId(), key))
            .isEqualTo("SENT");
    }

    @Test
    @DisplayName("The database refuses a FAILED row with no reason, independently of the code")
    void theSchemaRefusesAnUnexplainedFailure() {
        // V32's notification_log_failed_has_a_reason CHECK. Two layers state the same rule so
        // that neither has to rely on the other: DeliveryResult cannot construct such a value,
        // and the table cannot store one however it is reached.
        String id = UUID.randomUUID().toString();

        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO notification_log (id, user_id, channel, type, status) "
                + "VALUES (?::uuid, ?, 'IN_APP', 'WEEKLY_DIGEST', 'FAILED')",
            id, member.userId()))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("A disabled email channel writes no row at all, rather than a fake SENT one")
    void aDisabledChannelWritesNothing() {
        String key = UUID.randomUUID().toString();

        serviceWith(new DeadChannel()).notify(member.userId(), NotificationType.BUDGET_THRESHOLD,
            NotificationContent.of("A budget is at 92%", "body", key));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM notification_log WHERE user_id = ? AND channel = 'EMAIL'",
            Integer.class, member.userId()))
            .as("a member who has not opted in to email has no email delivery to record")
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM notification_log WHERE user_id = ? AND dedup_key = ?",
            String.class, member.userId(), key))
            .isEqualTo("SENT");
    }

    @Test
    @DisplayName("A member who silenced in-app notifications still gets the row, already read")
    void silencedNotificationsAreStillRecorded() {
        NotificationPreferencesUpdateRequest request = new NotificationPreferencesUpdateRequest();
        request.setInAppEnabled(false);
        preferences.update(member.userId(), request);
        String key = UUID.randomUUID().toString();

        serviceWith().notify(member.userId(), NotificationType.WEEKLY_DIGEST,
            NotificationContent.of("Silent week", "body", key));

        assertThat(unreadCount(member))
            .as("it does not nag")
            .isZero();
        assertThat(inbox(member))
            .as("but the record is kept — losing it would re-create finding D3")
            .anyMatch(row -> "Silent week".equals(row.path("title").asString()));
    }
}
