package finance.trove.notifications.send;

import finance.trove.common.time.Instants;
import finance.trove.notifications.NotificationChannel;
import finance.trove.notifications.NotificationType;
import finance.trove.notifications.preference.NotificationPreference;
import finance.trove.notifications.preference.NotificationPreferenceService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The in-process notification API — what {@code POST /internal/notifications/send} used to be
 * ({@code docs/recovery/06-COLLAPSE-PLAN.md} §2.8, §6 D3; finding D3).
 *
 * <p>The route, its controller, its request DTO, its integration test and the service token filter
 * that guarded it are all deleted. Producers now call {@link #notify} directly, which removes an
 * HTTP hop, a shared bearer token and a second serialisation of every notification, and which is
 * what lets the whole thing happen inside the producer's own transaction boundary.
 *
 * <h2>What notify does, in order, and why the order matters</h2>
 * <ol>
 *   <li><strong>Claim.</strong> One INSERT of the {@code IN_APP} {@code notification_log} row with
 *       this type's dedup key and {@code ON CONFLICT DO NOTHING}. Zero rows means this
 *       notification has already happened and the method returns having done nothing else. The
 *       claim comes FIRST so that two producers racing on the same key cannot both write an inbox
 *       row — the decision is made by V32's partial unique index, not by a read.</li>
 *   <li><strong>Preferences.</strong> Loaded, creating the shipped defaults if the member has
 *       none.</li>
 *   <li><strong>Channels.</strong> Each enabled channel is called and the REAL
 *       {@link DeliveryResult} is written — {@code SENT}, or {@code FAILED} with the reason. The
 *       {@code IN_APP} channel is always enabled and writes the {@code in_app_notification} row
 *       (finding D3's second half fixed): the inbox is the durable record of what this server told
 *       a member, so the row exists whatever the other channels do. The
 *       claim row carries the {@code IN_APP} outcome; any other channel gets its own row with a
 *       null dedup key, because the claim has already been taken and V32's index admits exactly
 *       one row per key.</li>
 * </ol>
 *
 * <p>{@code trove.notification.sent} (Counter, tags {@code channel} and {@code status}) is
 * incremented per channel — §5 item 9 names that meter, and nothing created it before this step.
 *
 * <h2>Transactions</h2>
 * {@link #notify} is {@code REQUIRED}: it joins the caller's transaction where there is one. Its
 * callers are the four {@code AFTER_COMMIT} handlers in {@code NotificationEventListener}, which are
 * {@code REQUIRES_NEW} — so by the time they call, the ledger change they describe is committed
 * and this work is in a transaction of its own. The digest calls it per recipient outside any
 * transaction of its own, so each recipient gets one.
 */
@Service
public class NotificationService {

    /** §5 item 9's meter. Tagged by channel and by outcome, so a failing channel is visible. */
    public static final String SENT_COUNTER = "trove.notification.sent";

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationLogRepository logs;
    private final NotificationPreferenceService preferences;
    private final List<DeliveryChannel> channels;
    private final MeterRegistry meters;
    private final Clock clock;

    /**
     * The constructor Spring uses. Annotated because the class has two and the container
     * refuses to pick one without it — the failure is at context refresh, which is where it
     * belongs, but the message would be about constructors rather than about the clock.
     */
    @Autowired
    public NotificationService(NotificationLogRepository logs,
                               NotificationPreferenceService preferences,
                               List<DeliveryChannel> channels, MeterRegistry meters) {
        this(logs, preferences, channels, meters, Instants.CLOCK);
    }

    /** Test seam: a fixed clock makes the {@code sentAt} stamp assertable. */
    public NotificationService(NotificationLogRepository logs,
                               NotificationPreferenceService preferences,
                               List<DeliveryChannel> channels, MeterRegistry meters,
                               Clock clock) {
        this.logs = logs;
        this.preferences = preferences;
        this.channels = List.copyOf(channels);
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * Notifies one member, once.
     *
     * @return whether anything was written. {@code false} means the dedup key had already been
     *         claimed — this notification has already happened — and is a normal outcome, not a
     *         failure
     */
    @Transactional
    public boolean notify(UUID userId, NotificationType type, NotificationContent content) {
        UUID claimId = UUID.randomUUID();
        int claimed = logs.claim(claimId, userId, NotificationChannel.IN_APP.name(), type.name(),
            content.subject(), content.body(), content.dedupKey());
        if (claimed == 0) {
            LOG.debug("notification {} for user {} is already claimed under key {}", type, userId,
                content.dedupKey());
            return false;
        }

        NotificationPreference preference = preferences.getOrCreate(userId);
        Instant at = clock.instant();

        for (DeliveryChannel channel : channels) {
            if (!channel.enabledFor(preference)) {
                continue;
            }
            DeliveryResult result = attempt(channel, userId, type, preference, content);
            if (channel.channel() == NotificationChannel.IN_APP) {
                recordOnClaim(userId, claimId, result, at);
            } else {
                recordExtraChannel(userId, channel.channel(), type, content, result, at);
            }
            meters.counter(SENT_COUNTER, "channel", channel.channel().name(),
                "status", result.status().name()).increment();
        }
        return true;
    }

    /**
     * Writes a {@code FAILED} row for a notification that could not even be built.
     *
     * <p>The weekly digest needs this. Its per-recipient work reads the ledger and formats
     * figures, and any of that can fail; the source's job caught the exception, counted a "skip"
     * and logged, which is how it reported success with nothing sent for as long as it ran
     * (defect D-N1). A failure that produces no row is indistinguishable from a member who was
     * never a recipient, so it gets one — with the reason, and under the same dedup key, so that
     * the next run does not retry a deterministic failure for ever.
     *
     * <p>{@code REQUIRES_NEW}: it is called from a {@code catch}, and the transaction the failure
     * happened in is being rolled back. Without a new one the row would be rolled back with it —
     * the same reason {@code SecurityEventRecorder.record} is {@code REQUIRES_NEW}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID userId, NotificationType type, NotificationContent content,
                              String reason) {
        UUID id = UUID.randomUUID();
        int claimed = logs.claim(id, userId, NotificationChannel.IN_APP.name(), type.name(),
            content.subject(), content.body(), content.dedupKey());
        if (claimed == 0) {
            return;
        }
        logs.findByIdAndUserId(id, userId).ifPresent(row -> {
            row.record(DeliveryResult.failed(reason), clock.instant());
            logs.save(row);
        });
        meters.counter(SENT_COUNTER, "channel", NotificationChannel.IN_APP.name(),
            "status", DeliveryResult.Status.FAILED.name()).increment();
    }

    /**
     * Calls a channel and converts a thrown exception into the FAILED result the channel should
     * have returned.
     *
     * <p>A channel is not supposed to throw — {@link DeliveryChannel#send} returns what happened —
     * but one that does must not take the inbox row down with it. That was the shape of the
     * original defect from the other side: the dispatcher assumed nothing could fail and so wrote
     * SENT before it tried.
     */
    private DeliveryResult attempt(DeliveryChannel channel, UUID userId, NotificationType type,
                                   NotificationPreference preference,
                                   NotificationContent content) {
        try {
            DeliveryResult result = channel.send(userId, type, preference, content);
            return result == null
                ? DeliveryResult.failed(channel.channel() + " returned no delivery result")
                : result;
        } catch (Exception e) {
            // swallow-ok: converted into the FAILED result the channel owed us and written to
            // notification_log.error_message below. Rethrowing would roll back the inbox row that
            // is meant to survive a channel failure, which is finding D3.
            LOG.warn("channel {} threw delivering {} to user {}", channel.channel(), type, userId,
                e);
            return DeliveryResult.failed(e);
        }
    }

    private void recordOnClaim(UUID userId, UUID claimId, DeliveryResult result, Instant at) {
        logs.findByIdAndUserId(claimId, userId).ifPresent(row -> {
            row.record(result, at);
            logs.save(row);
        });
    }

    /**
     * A second channel's row.
     *
     * <p>{@code dedupKey} is null on purpose. The claim has already been taken by the
     * {@code IN_APP} row and V32's unique index admits exactly one row per
     * {@code (user, type, key)}; this row is a RECORD of an attempt rather than a second gate, and
     * a null key is precisely what "not deduplicated" means there.
     */
    private void recordExtraChannel(UUID userId, NotificationChannel channel,
                                    NotificationType type, NotificationContent content,
                                    DeliveryResult result, Instant at) {
        NotificationLog row = new NotificationLog(UUID.randomUUID(), userId, channel, type,
            content.subject(), content.body(), null);
        row.record(result, at);
        logs.save(row);
    }
}
