package finance.trove.notifications.send;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.preference.NotificationPreference;
import finance.trove.notifications.preference.NotificationPreferenceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

/**
 * {@code NotificationService} by behaviour: the deduplication claim, the always-written inbox row,
 * and the delivery status actually being the channel's answer (finding D3).
 *
 * <p>The regression gate is {@link #aFailingChannelIsRecordedAsFailedWithItsReason()}: it fails
 * against the pre-collapse shape, where the status was set to {@code SENT} before anything was
 * attempted and no channel could report otherwise.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-07T08:00:00Z");

    @Mock
    private NotificationLogRepository logs;

    @Mock
    private NotificationPreferenceService preferences;

    private SimpleMeterRegistry meters;
    private NotificationPreference preference;
    private final List<NotificationLog> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        preference = NotificationPreference.defaultsFor(USER);
        when(preferences.getOrCreate(USER)).thenReturn(preference);
        when(logs.claim(any(), any(), anyString(), anyString(), anyString(), any(), any()))
            .thenAnswer(invocation -> {
                NotificationLog row = new NotificationLog(invocation.getArgument(0),
                    invocation.getArgument(1),
                    NotificationChannel.valueOf(invocation.getArgument(2)),
                    NotificationType.valueOf(invocation.getArgument(3)),
                    invocation.getArgument(4), invocation.getArgument(5),
                    invocation.getArgument(6));
                saved.add(row);
                when(logs.findByIdAndUserId(row.getId(), USER)).thenReturn(Optional.of(row));
                return 1;
            });
        when(logs.save(any(NotificationLog.class))).thenAnswer(invocation -> {
            NotificationLog row = invocation.getArgument(0);
            if (!saved.contains(row)) {
                saved.add(row);
            }
            return row;
        });
    }

    private NotificationService service(DeliveryChannel... channels) {
        return new NotificationService(logs, preferences, List.of(channels), meters,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** A channel that answers whatever it is told to, and remembers whether it was called. */
    private static final class StubChannel implements DeliveryChannel {

        private final NotificationChannel channel;
        private final boolean enabled;
        private final DeliveryResult result;
        private final RuntimeException thrown;
        private int calls;

        private StubChannel(NotificationChannel channel, boolean enabled, DeliveryResult result,
                            RuntimeException thrown) {
            this.channel = channel;
            this.enabled = enabled;
            this.result = result;
            this.thrown = thrown;
        }

        static StubChannel sending(NotificationChannel channel) {
            return new StubChannel(channel, true, DeliveryResult.sent(), null);
        }

        static StubChannel failing(NotificationChannel channel, String reason) {
            return new StubChannel(channel, true, DeliveryResult.failed(reason), null);
        }

        static StubChannel throwing(NotificationChannel channel, RuntimeException e) {
            return new StubChannel(channel, true, null, e);
        }

        static StubChannel disabled(NotificationChannel channel) {
            return new StubChannel(channel, false, DeliveryResult.sent(), null);
        }

        @Override
        public NotificationChannel channel() {
            return channel;
        }

        @Override
        public boolean enabledFor(NotificationPreference preference) {
            return enabled;
        }

        @Override
        public DeliveryResult send(UUID userId, NotificationType type,
                                   NotificationPreference preference,
                                   NotificationContent content) {
            calls++;
            if (thrown != null) {
                throw thrown;
            }
            return result;
        }
    }

    @Test
    @DisplayName("a claim that inserts nothing stops the notification dead")
    void anAlreadyClaimedNotificationDoesNothingElse() {
        // Zero rows from the ON CONFLICT insert is the whole deduplication decision. Nothing after
        // it may run: not the inbox row, not a channel, not a counter.
        // doReturn, not when(...): a when() would CALL the mock and run the setUp answer above
        // with null arguments before it could replace it.
        org.mockito.Mockito.doReturn(0).when(logs)
            .claim(any(), any(), anyString(), anyString(), anyString(), any(), any());
        StubChannel inApp = StubChannel.sending(NotificationChannel.IN_APP);

        boolean written = service(inApp).notify(USER, NotificationType.BUDGET_THRESHOLD,
            NotificationContent.of("subject", "body", "envelope:2026-09"));

        assertThat(written).isFalse();
        assertThat(inApp.calls).isZero();
        verify(preferences, never()).getOrCreate(any());
        assertThat(meters.find(NotificationService.SENT_COUNTER).counters()).isEmpty();
    }

    @Test
    @DisplayName("a failing channel is recorded as FAILED with its reason, and the inbox row stands")
    void aFailingChannelIsRecordedAsFailedWithItsReason() {
        // The D3 regression gate. Against the pre-collapse shape this fails twice over: the row
        // would say SENT, and error_message would be null.
        preference.setEmailEnabled(true);
        preference.setEmailAddress("member@trove.test");
        StubChannel inApp = StubChannel.sending(NotificationChannel.IN_APP);
        StubChannel email = StubChannel.failing(NotificationChannel.EMAIL,
            "Connection refused to smtp.trove.test:25");

        boolean written = service(inApp, email).notify(USER, NotificationType.WEEKLY_DIGEST,
            NotificationContent.of("Your week", "body", "2026-W36"));

        assertThat(written).isTrue();
        assertThat(inApp.calls).as("the inbox row is written even though a channel failed")
            .isEqualTo(1);

        NotificationLog inAppRow = rowFor(NotificationChannel.IN_APP);
        assertThat(inAppRow.getStatus()).isEqualTo(NotificationLog.Status.SENT);
        assertThat(inAppRow.getErrorMessage()).isNull();
        assertThat(inAppRow.getSentAt()).isEqualTo(NOW);

        NotificationLog emailRow = rowFor(NotificationChannel.EMAIL);
        assertThat(emailRow.getStatus()).isEqualTo(NotificationLog.Status.FAILED);
        assertThat(emailRow.getErrorMessage())
            .as("a FAILED row without a reason is the D3 defect")
            .isEqualTo("Connection refused to smtp.trove.test:25");
        assertThat(emailRow.getSentAt()).isNull();
    }

    @Test
    @DisplayName("only the claim row carries the dedup key; a second channel's row carries none")
    void onlyTheClaimRowIsDeduplicated() {
        // V32's unique index admits exactly one row per (user, type, key). The claim has already
        // been taken by the IN_APP row, so the EMAIL row must carry a null key or its INSERT
        // would conflict with the very row that authorised it.
        preference.setEmailEnabled(true);
        preference.setEmailAddress("member@trove.test");

        service(StubChannel.sending(NotificationChannel.IN_APP),
            StubChannel.sending(NotificationChannel.EMAIL))
            .notify(USER, NotificationType.WEEKLY_DIGEST,
                NotificationContent.of("Your week", "body", "2026-W36"));

        assertThat(rowFor(NotificationChannel.IN_APP).getDedupKey()).isEqualTo("2026-W36");
        assertThat(rowFor(NotificationChannel.EMAIL).getDedupKey()).isNull();
    }

    @Test
    @DisplayName("a channel that throws is converted into a FAILED result, not an aborted notify")
    void aThrowingChannelDoesNotTakeTheInboxRowWithIt() {
        preference.setEmailEnabled(true);
        preference.setEmailAddress("member@trove.test");

        boolean written = service(StubChannel.sending(NotificationChannel.IN_APP),
            StubChannel.throwing(NotificationChannel.EMAIL,
                new IllegalStateException("mail session closed")))
            .notify(USER, NotificationType.SYNC_FAILED,
                NotificationContent.of("Sync failed", "body", "account:2026-09-07"));

        assertThat(written).isTrue();
        assertThat(rowFor(NotificationChannel.EMAIL).getStatus())
            .isEqualTo(NotificationLog.Status.FAILED);
        assertThat(rowFor(NotificationChannel.EMAIL).getErrorMessage())
            .isEqualTo("mail session closed");
    }

    @Test
    @DisplayName("a disabled channel is not called and writes no row")
    void aDisabledChannelIsSkipped() {
        StubChannel email = StubChannel.disabled(NotificationChannel.EMAIL);

        service(StubChannel.sending(NotificationChannel.IN_APP), email)
            .notify(USER, NotificationType.BUDGET_THRESHOLD,
                NotificationContent.of("subject", "body", "envelope:2026-09"));

        assertThat(email.calls).isZero();
        assertThat(saved).extracting(NotificationLog::getChannel)
            .containsExactly(NotificationChannel.IN_APP);
    }

    @Test
    @DisplayName("trove.notification.sent counts per channel and per status")
    void theMeterIsTaggedByChannelAndStatus() {
        // §5 item 9 names this meter and nothing created it before this step. Tagging by status
        // as well as channel is what makes a silently failing channel visible on a dashboard.
        preference.setEmailEnabled(true);
        preference.setEmailAddress("member@trove.test");

        service(StubChannel.sending(NotificationChannel.IN_APP),
            StubChannel.failing(NotificationChannel.EMAIL, "refused"))
            .notify(USER, NotificationType.WEEKLY_DIGEST,
                NotificationContent.of("Your week", "body", "2026-W36"));

        assertThat(meters.get(NotificationService.SENT_COUNTER)
            .tags("channel", "IN_APP", "status", "SENT").counter().count()).isEqualTo(1.0);
        assertThat(meters.get(NotificationService.SENT_COUNTER)
            .tags("channel", "EMAIL", "status", "FAILED").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordFailure writes a FAILED row for a notification that could never be built")
    void recordFailureLeavesAnHonestRow() {
        // The digest's per-recipient catch. The source counted a "skip" and logged, which is
        // indistinguishable from a member who was never a recipient — and is how defect D-N1
        // stayed invisible for the life of the service.
        service(StubChannel.sending(NotificationChannel.IN_APP))
            .recordFailure(USER, NotificationType.WEEKLY_DIGEST,
                NotificationContent.of("Your week", "body", "2026-W36"),
                "the ledger read timed out");

        NotificationLog row = saved.get(0);
        assertThat(row.getStatus()).isEqualTo(NotificationLog.Status.FAILED);
        assertThat(row.getErrorMessage()).isEqualTo("the ledger read timed out");
        assertThat(row.getDedupKey()).isEqualTo("2026-W36");
        assertThat(meters.get(NotificationService.SENT_COUNTER)
            .tags("channel", "IN_APP", "status", "FAILED").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordFailure does not overwrite a notification that was already delivered")
    void recordFailureRespectsTheClaim() {
        org.mockito.Mockito.doReturn(0).when(logs)
            .claim(any(), any(), anyString(), anyString(), anyString(), any(), eq("2026-W36"));

        service(StubChannel.sending(NotificationChannel.IN_APP))
            .recordFailure(USER, NotificationType.WEEKLY_DIGEST,
                NotificationContent.of("Your week", "body", "2026-W36"), "too late");

        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("the subject and body reach the log row so an operator can see what was sent")
    void theRowCarriesWhatWasSaid() {
        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);

        service(StubChannel.sending(NotificationChannel.IN_APP))
            .notify(USER, NotificationType.SUBSCRIPTION_PRICE_CHANGE,
                NotificationContent.of("Netflix rose to CAD 22.99", "body", "sub:22.99"));

        verify(logs).claim(any(), eq(USER), eq("IN_APP"), eq("SUBSCRIPTION_PRICE_CHANGE"),
            subject.capture(), eq("body"), eq("sub:22.99"));
        assertThat(subject.getValue()).isEqualTo("Netflix rose to CAD 22.99");
    }

    private NotificationLog rowFor(NotificationChannel channel) {
        return saved.stream().filter(row -> row.getChannel() == channel).findFirst()
            .orElseThrow(() -> new AssertionError("no " + channel + " row was written"));
    }
}
