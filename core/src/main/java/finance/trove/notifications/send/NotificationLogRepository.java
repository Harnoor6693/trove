package finance.trove.notifications.send;

import finance.trove.common.repository.UserOwnedRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code notification_log} (V32) — one row per delivery attempt, with its real outcome.
 *
 * <p>Extends {@link UserOwnedRepository}: the rows have a surrogate id and belong to one member,
 * and there is no route that reads them, so every finder here names a user because the base makes
 * it so rather than because a caller needs it to.
 */
public interface NotificationLogRepository extends UserOwnedRepository<NotificationLog, UUID> {

    /**
     * Takes the deduplication claim for one notification, atomically.
     *
     * <p><strong>This one statement is the deduplication.</strong> V32 carries a partial unique
     * index over {@code (user_id, type, dedup_key)}, and {@code ON CONFLICT DO NOTHING} turns a
     * second producer's attempt into zero rows affected rather than an exception or a duplicate
     * notification. Two listeners racing on the same envelope in the same month therefore send
     * once, and which of them wins is decided by Postgres rather than by the order two threads
     * happened to read a table in.
     *
     * <p>Native SQL because JPQL has no {@code ON CONFLICT}; the id is supplied by the caller
     * because a statement that inserts nothing returns nothing, so the caller has to know the id
     * it would have written in order to load the row it did write.
     *
     * <p>A {@code null} {@code dedupKey} never conflicts — the index is partial — so a
     * notification that is deliberately not deduplicated always claims.
     *
     * @return 1 when the claim was taken, 0 when this notification has already happened
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO notification_log "
        + "(id, user_id, channel, type, subject, body, status, dedup_key) "
        + "VALUES (:id, :userId, :channel, :type, :subject, :body, 'PENDING', :dedupKey) "
        + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("userId") UUID userId,
              @Param("channel") String channel, @Param("type") String type,
              @Param("subject") String subject, @Param("body") String body,
              @Param("dedupKey") String dedupKey);
}
