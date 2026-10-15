package finance.trove.notifications.digest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.common.domain.CurrencyCode;
import finance.trove.common.domain.Jurisdiction;
import finance.trove.notifications.NotificationProperties;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.preference.NotificationPreferenceRepository;
import finance.trove.notifications.send.NotificationContent;
import finance.trove.notifications.send.NotificationService;
import finance.trove.users.User;
import finance.trove.users.UserRepository;
import finance.trove.users.UserRole;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The digest job's loop: paging, interruption, and what happens to one recipient's failure.
 *
 * <p>The last of those is the interesting one. Defect D-N1's real damage was not the broken HTTP
 * call — it was the {@code catch} that turned it into a "skipped" counter, so the job reported
 * success with nothing sent every Monday for as long as it existed. A failure here lands in
 * {@code notification_log} as {@code FAILED} with its reason, and the loop carries on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WeeklyDigestJobTest {

    private static final UUID HOUSEHOLD = UUID.randomUUID();
    private static final LocalDate WEEK_START = LocalDate.of(2026, 8, 31);

    @Mock
    private NotificationPreferenceRepository preferences;

    @Mock
    private UserRepository users;

    @Mock
    private WeeklyDigestBuilder builder;

    @Mock
    private NotificationService notifications;

    private WeeklyDigestJob job;

    @BeforeEach
    void setUp() {
        job = new WeeklyDigestJob(preferences, users, builder, notifications,
            new NotificationProperties(null, null, null, 2, null, null));
        when(builder.weekStartFor(any())).thenReturn(WEEK_START);
        when(builder.summarise(any(), any(), any())).thenReturn(List.of());
        when(builder.contentFor(any(), any(), any()))
            .thenReturn(NotificationContent.of("Your week", "body", "2026-W36"));
        when(notifications.notify(any(), any(), any())).thenReturn(true);
    }

    private UUID activeMember() {
        UUID id = UUID.randomUUID();
        User user = new User(HOUSEHOLD, UserRole.MEMBER, id + "@trove.test", "Member",
            Jurisdiction.CA, CurrencyCode.CAD, ZoneId.of("America/Toronto"));
        ReflectionTestUtils.setField(user, "id", id);
        ReflectionTestUtils.setField(user, "active", true);
        when(users.findAccount(id)).thenReturn(Optional.of(user));
        return id;
    }

    @Test
    @DisplayName("Recipients are read a page at a time until a page comes back empty")
    void recipientsArePaged() {
        // An install with ten thousand members must not put them all on the heap at once, and
        // nothing is committed across pages — the ISO-week dedup key is what makes a resumed run
        // safe, not any state this job keeps.
        UUID first = activeMember();
        UUID second = activeMember();
        UUID third = activeMember();
        when(preferences.findDigestRecipients(any(Pageable.class)))
            .thenReturn(List.of(first, second))
            .thenReturn(List.of(third))
            .thenReturn(List.of());

        assertThat(job.sendAllDue()).isEqualTo(3);
        verify(notifications).notify(eq(first), eq(NotificationType.WEEKLY_DIGEST), any());
        verify(notifications).notify(eq(third), eq(NotificationType.WEEKLY_DIGEST), any());
    }

    @Test
    @DisplayName("The scheduled entry point runs the same loop")
    void theScheduledMethodRunsTheLoop() {
        UUID only = activeMember();
        when(preferences.findDigestRecipients(any(Pageable.class)))
            .thenReturn(List.of(only))
            .thenReturn(List.of());

        job.sendWeeklyDigests();

        verify(notifications).notify(eq(only), eq(NotificationType.WEEKLY_DIGEST), any());
    }

    @Test
    @DisplayName("A member already sent this week's digest is not counted again")
    void anAlreadySentDigestIsNotCounted() {
        UUID member = activeMember();
        when(preferences.findDigestRecipients(any(Pageable.class)))
            .thenReturn(List.of(member))
            .thenReturn(List.of());
        when(notifications.notify(any(), any(), any())).thenReturn(false);

        assertThat(job.sendAllDue())
            .as("notify answers false when the ISO-week key was already claimed, which is a "
                + "normal outcome rather than a failure")
            .isZero();
    }

    @Test
    @DisplayName("One recipient's failure is recorded as FAILED and the loop carries on")
    void aFailureIsRecordedAndTheLoopContinues() {
        UUID broken = activeMember();
        UUID healthy = activeMember();
        when(preferences.findDigestRecipients(any(Pageable.class)))
            .thenReturn(List.of(broken, healthy))
            .thenReturn(List.of());
        when(builder.summarise(eq(HOUSEHOLD), any(), eq(WEEK_START)))
            .thenAnswer(invocation -> {
                throw new IllegalStateException("the ledger read timed out");
            })
            .thenReturn(List.of());

        assertThat(job.sendAllDue()).isEqualTo(1);

        verify(notifications).recordFailure(eq(broken), eq(NotificationType.WEEKLY_DIGEST), any(),
            eq("the ledger read timed out"));
        verify(notifications).notify(eq(healthy), eq(NotificationType.WEEKLY_DIGEST), any());
    }

    @Test
    @DisplayName("A failure BEFORE the week can be computed is recorded, and the loop carries on")
    void aFailureBeforeTheWeekIsKnownStillLetsTheLoopContinue() {
        // The account read and the week computation used to sit ABOVE the try, so a repository
        // error or a recipient whose stored timezone no longer resolves threw straight out of
        // sendOne, out of sendAllDue's loop, and abandoned every remaining recipient of that
        // Monday with no row recorded for any of them. aFailureIsRecordedAndTheLoopContinues
        // cannot see it: builder.summarise is inside the try, and always was.
        UUID broken = activeMember();
        UUID healthy = activeMember();
        when(preferences.findDigestRecipients(any(Pageable.class)))
            .thenReturn(List.of(broken, healthy))
            .thenReturn(List.of());
        when(builder.weekStartFor(broken))
            .thenThrow(new java.time.zone.ZoneRulesException("Unknown time-zone ID: Mars/Olympus"));

        assertThat(job.sendAllDue())
            .as("one recipient's bad data must not cost every other member their digest")
            .isEqualTo(1);

        verify(notifications).recordFailure(eq(broken), eq(NotificationType.WEEKLY_DIGEST), any(),
            eq("Unknown time-zone ID: Mars/Olympus"));
        verify(notifications).notify(eq(healthy), eq(NotificationType.WEEKLY_DIGEST), any());
    }

    @Test
    @DisplayName("A recipient whose account row cannot be read is recorded, not thrown")
    void anUnreadableAccountRowIsRecorded() {
        UUID broken = UUID.randomUUID();
        UUID healthy = activeMember();
        when(preferences.findDigestRecipients(any(Pageable.class)))
            .thenReturn(List.of(broken, healthy))
            .thenReturn(List.of());
        when(users.findAccount(broken))
            .thenThrow(new org.springframework.dao.QueryTimeoutException("statement timeout"));

        assertThat(job.sendAllDue()).isEqualTo(1);

        verify(notifications).recordFailure(eq(broken), eq(NotificationType.WEEKLY_DIGEST), any(),
            eq("statement timeout"));
        verify(notifications).notify(eq(healthy), eq(NotificationType.WEEKLY_DIGEST), any());
    }

    @Test
    @DisplayName("A failure that cannot even be recorded still lets the loop finish")
    void anUnrecordableFailureDoesNotStopTheRun() {
        UUID broken = activeMember();
        UUID healthy = activeMember();
        when(preferences.findDigestRecipients(any(Pageable.class)))
            .thenReturn(List.of(broken, healthy))
            .thenReturn(List.of());
        when(builder.weekStartFor(broken))
            .thenThrow(new IllegalStateException("the ledger read timed out"));
        org.mockito.Mockito.doThrow(new IllegalStateException("the database is gone"))
            .when(notifications).recordFailure(eq(broken), any(), any(), any());

        assertThat(job.sendAllDue())
            .as("there is nowhere left to record it, and it still must not cost the rest of the "
                + "install its digest")
            .isEqualTo(1);
        verify(notifications).notify(eq(healthy), eq(NotificationType.WEEKLY_DIGEST), any());
    }

    @Test
    @DisplayName("An account that has gone, or been deactivated, is simply not a recipient")
    void anAbsentAccountIsSkipped() {
        UUID gone = UUID.randomUUID();
        when(users.findAccount(gone)).thenReturn(Optional.empty());

        UUID deactivated = activeMember();
        ReflectionTestUtils.setField(users.findAccount(deactivated).orElseThrow(), "active",
            false);

        assertThat(job.sendOne(gone)).isFalse();
        assertThat(job.sendOne(deactivated)).isFalse();
        verify(notifications, never()).notify(any(), any(), any());
        verify(notifications, never()).recordFailure(any(), any(), any(), any());
    }

    @Test
    @DisplayName("An interrupted run stops between recipients and reports what it did send")
    void interruptionStopsBetweenRecipients() throws Exception {
        UUID one = activeMember();
        UUID two = activeMember();
        when(preferences.findDigestRecipients(any(Pageable.class)))
            .thenReturn(List.of(one, two))
            .thenReturn(List.of());

        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            assertThat(job.sendAllDue())
                .as("the interrupt is seen before the first recipient")
                .isZero();
        });
        worker.start();
        worker.join(5_000);

        assertThat(worker.isAlive()).isFalse();
        verify(notifications, never()).notify(any(), any(), any());
    }
}
