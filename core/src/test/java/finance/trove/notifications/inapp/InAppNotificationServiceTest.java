package finance.trove.notifications.inapp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import finance.trove.common.exception.ResourceNotFoundException;
import finance.trove.notifications.NotificationType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The inbox reads and writes, including the "not yours is 404" rule. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InAppNotificationServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();
    private static final Instant FIRST = Instant.parse("2026-09-07T08:00:00Z");
    private static final Instant LATER = Instant.parse("2026-09-07T09:30:00Z");

    @Mock
    private InAppNotificationRepository repository;

    private InAppNotificationService serviceAt(Instant instant) {
        return new InAppNotificationService(repository,
            Clock.fixed(instant, ZoneOffset.UTC));
    }

    @BeforeEach
    void setUp() {
        when(repository.save(any(InAppNotification.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private InAppNotification row() {
        return new InAppNotification(USER, NotificationType.BUDGET_THRESHOLD, "title", "body",
            "/budgets/2026-09");
    }

    @Test
    @DisplayName("a notification that is not the caller's is a 404, not a 403")
    void anotherMembersRowIsNotFound() {
        // Including a household mate's. A 403 would confirm the row exists, which over enough
        // probes is an enumeration oracle for another member's inbox.
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, OTHER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt(FIRST).markRead(OTHER, id))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> serviceAt(FIRST).dismiss(OTHER, id))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("marking read twice keeps the first readAt")
    void markReadIsIdempotentAndKeepsTheOriginalStamp() {
        // readAt answers "when did they first see this". Refreshing it on a repeat call would
        // make it answer "when did the client last retry", which is a different question.
        InAppNotification notification = row();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, USER)).thenReturn(Optional.of(notification));

        serviceAt(FIRST).markRead(USER, id);
        assertThat(notification.isRead()).isTrue();
        assertThat(notification.getReadAt()).isEqualTo(FIRST);

        serviceAt(LATER).markRead(USER, id);
        assertThat(notification.getReadAt()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("stamps are held at microsecond precision, as Postgres will store them")
    void stampsAreTruncatedToWhatTheColumnStores() {
        // timestamptz keeps microseconds. A Linux clock reads nanoseconds, so a stamp kept as-is
        // is answered one way by the call that wrote it and another by every read after it.
        Instant nanos = FIRST.plusNanos(123_456_789);
        InAppNotification notification = row();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, USER)).thenReturn(Optional.of(notification));

        serviceAt(nanos).markRead(USER, id);
        assertThat(notification.getReadAt()).isEqualTo(FIRST.plusNanos(123_456_000));

        InAppNotification other = row();
        when(repository.findByIdAndUserId(id, USER)).thenReturn(Optional.of(other));
        serviceAt(nanos).dismiss(USER, id);
        assertThat(other.getDismissedAt()).isEqualTo(FIRST.plusNanos(123_456_000));
    }

    @Test
    @DisplayName("dismissing also marks read, so the badge cannot count a hidden row")
    void dismissingAlsoReads() {
        InAppNotification notification = row();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, USER)).thenReturn(Optional.of(notification));

        serviceAt(FIRST).dismiss(USER, id);

        assertThat(notification.isDismissed()).isTrue();
        assertThat(notification.getDismissedAt()).isEqualTo(FIRST);
        assertThat(notification.isRead())
            .as("the badge query excludes dismissed rows anyway; leaving read false would make "
                + "the row reappear as unread if it were ever un-dismissed")
            .isTrue();
    }

    @Test
    @DisplayName("dismissing twice is a no-op that keeps the first dismissedAt")
    void dismissIsIdempotent() {
        InAppNotification notification = row();
        UUID id = UUID.randomUUID();
        when(repository.findByIdAndUserId(id, USER)).thenReturn(Optional.of(notification));

        serviceAt(FIRST).dismiss(USER, id);
        serviceAt(LATER).dismiss(USER, id);

        assertThat(notification.getDismissedAt()).isEqualTo(FIRST);
    }

    @Test
    @DisplayName("record() writes the row unread when it should announce itself")
    void recordAnnouncesByDefault() {
        InAppNotification written = serviceAt(FIRST).record(USER,
            NotificationType.WEEKLY_DIGEST, "Your week", "body", "/dashboard", true);

        assertThat(written.isRead()).isFalse();
        assertThat(written.getReadAt()).isNull();
        assertThat(written.isDismissed()).isFalse();
    }

    @Test
    @DisplayName("record() writes the row already read when the member silenced announcements")
    void recordSilentlyStillWritesTheRow() {
        InAppNotification written = serviceAt(FIRST).record(USER,
            NotificationType.WEEKLY_DIGEST, "Your week", "body", "/dashboard", false);

        assertThat(written.isRead()).isTrue();
        assertThat(written.getReadAt()).isEqualTo(FIRST);
        assertThat(written.isDismissed())
            .as("silenced is not hidden: the row is still in the inbox, it simply does not nag")
            .isFalse();
    }
}
