package finance.trove.ai.insight;

import finance.trove.common.repository.UserOwnedRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly insights, always scoped to the member they were written for (schema V40).
 *
 * <p>{@link UserOwnedRepository} rather than {@code UserScopedRepository}: these rows are per
 * member, not per household, and there is no route by which a household mate reaches one.
 */
public interface CoachInsightRepository extends UserOwnedRepository<CoachInsight, UUID> {

    /**
     * One page of this member's notes, newest first.
     *
     * <p>Named rather than derived so that the ordering is stated once here instead of in the two
     * callers, and so that the count query is the obvious one.
     */
    @Query(value = "select i from CoachInsight i where i.userId = :userId "
        + "order by i.weekStart desc, i.createdAt desc",
        countQuery = "select count(i) from CoachInsight i where i.userId = :userId")
    Page<CoachInsight> pageForUser(@Param("userId") UUID userId, Pageable pageable);

    /**
     * Writes one note if the member does not already have one for that week.
     *
     * <p><strong>Native, because {@code ON CONFLICT} is the whole point.</strong> The weekly job
     * holds a ShedLock, but {@code lockAtMostFor} expiry is exactly the case where two nodes run it
     * at once: the lock is released by the clock while its holder is still working. Without a key
     * to conflict on, that run doubles every member's inbox. With one, the second insert is free.
     * JPA has no vocabulary for a conflict clause, and a read-then-write in Java would be the same
     * race with more steps.
     *
     * <p>{@code @Transactional} is not optional: Spring Data runs a query method inside
     * {@code SimpleJpaRepository}'s class-level read-only transaction unless told otherwise, and
     * Postgres refuses an INSERT in a read-only transaction.
     *
     * @param contentEncrypted {@code FieldEncryptionService} ciphertext, never plaintext
     * @return 1 when a row was written, 0 when this member already had one for that week
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO coach_insight (user_id, week_start, content_encrypted) "
        + "VALUES (:userId, :weekStart, :contentEncrypted) "
        + "ON CONFLICT (user_id, week_start) DO NOTHING",
        nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId,
                       @Param("weekStart") LocalDate weekStart,
                       @Param("contentEncrypted") String contentEncrypted);
}
