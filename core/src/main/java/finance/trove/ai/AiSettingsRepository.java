package finance.trove.ai;

import finance.trove.common.repository.UserKeyedRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code ai_settings} is keyed by the user id itself, so this extends
 * {@link UserKeyedRepository}: every finder it has takes a user id and there is no {@code findById}
 * to reach for by accident.
 *
 * <p>That is not a stylistic choice here. A household administrator may delete a member and clear
 * their second factor, but nobody — administrator included — reads another member's AI endpoint,
 * API key or privacy tier (owner decision 9). A repository with an unscoped finder would make that
 * a code-review question; with this base it is a compile error.
 */
public interface AiSettingsRepository extends UserKeyedRepository<AiSettings> {

    /**
     * Writes the outcome of a connection test, and nothing else.
     *
     * <p>A statement rather than a {@code save()} because the caller is deliberately outside a
     * transaction and has been waiting on somebody else's HTTP server — for up to two minutes —
     * since it read the row. Saving the detached snapshot back would merge every column of it,
     * so a key the member deleted, or an {@code enabled: false} they saved, during that window
     * would come back. There is no {@code @Version} on the entity to catch it either. Two columns
     * belong to the test, and this writes those two.
     *
     * <p>Its own transaction ({@code REQUIRED} with no caller transaction is a new one), because a
     * JPQL update needs one and this path has none.
     *
     * @return 1 when a row was updated, 0 when the member has never saved settings
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AiSettings s set s.lastTestAt = :at, s.lastTestOk = :ok, s.updatedAt = :at "
        + "where s.userId = :userId")
    int recordTestOutcome(@Param("userId") UUID userId, @Param("at") Instant at,
                          @Param("ok") boolean ok);

    /**
     * The member's settings, unless the member is no longer active (B3-14).
     *
     * <p>What {@link AiGateway#forUser} reads. A member an administrator deleted is inactive at
     * once but their row lives through the seven-day grace, and a receipt they uploaded can still
     * be re-run by the orphan sweep in that time; read through {@code findByUserId}, the sweep
     * spent their key at their endpoint on their behalf. {@code is_active} is how every other
     * background path already tells a member who has left (UserAdminService#deleteMember).
     */
    @Query("select s from AiSettings s where s.userId = :userId and exists "
        + "(select u.id from User u where u.id = s.userId and u.active = true)")
    Optional<AiSettings> findForActiveUser(@Param("userId") UUID userId);
}
