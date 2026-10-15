package finance.trove.notifications.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.inapp.InAppNotificationService;
import finance.trove.notifications.preference.NotificationPreference;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The channel that is always attempted, and what {@code inAppEnabled} actually governs. */
@ExtendWith(MockitoExtension.class)
class InAppChannelTest {

    private static final UUID USER = UUID.randomUUID();

    @Mock
    private InAppNotificationService inbox;

    @Test
    @DisplayName("the inbox channel is enabled for everyone, whatever the preference says")
    void theInboxIsNeverSwitchedOff() {
        // The row is the record of what this server told a member. Making the WRITE conditional
        // would put finding D3's hole — five read endpoints over a table nothing fills — back
        // behind a checkbox.
        NotificationPreference off = NotificationPreference.defaultsFor(USER);
        off.setInAppEnabled(false);

        assertThat(new InAppChannel(inbox).enabledFor(off)).isTrue();
        assertThat(new InAppChannel(inbox).channel()).isEqualTo(NotificationChannel.IN_APP);
    }

    @Test
    @DisplayName("with announcements on, the row arrives unread")
    void anAnnouncedNotificationArrivesUnread() {
        NotificationPreference preference = NotificationPreference.defaultsFor(USER);

        DeliveryResult result = new InAppChannel(inbox).send(USER,
            NotificationType.BUDGET_THRESHOLD, preference,
            NotificationContent.of("subject", "body", "/budgets/2026-09", "key"));

        assertThat(result.isSent()).isTrue();
        verify(inbox).record(eq(USER), eq(NotificationType.BUDGET_THRESHOLD), eq("subject"),
            eq("body"), eq("/budgets/2026-09"), eq(true));
    }

    @Test
    @DisplayName("with announcements off, the row is still written — already read")
    void aSilencedNotificationIsStillRecorded() {
        // "Do not interrupt me" and "do not keep a record" are different requests, and only the
        // first is one a preference should be able to make.
        NotificationPreference preference = NotificationPreference.defaultsFor(USER);
        preference.setInAppEnabled(false);

        new InAppChannel(inbox).send(USER, NotificationType.WEEKLY_DIGEST, preference,
            NotificationContent.of("subject", "body", "2026-W36"));

        verify(inbox).record(eq(USER), eq(NotificationType.WEEKLY_DIGEST), any(), any(), any(),
            eq(false));
        verify(inbox, org.mockito.Mockito.never())
            .record(any(), any(), any(), any(), any(), eq(true));
    }
}
