package finance.trove.notifications.preference;

import finance.trove.common.repository.UserKeyedRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code notification_preference} is keyed by the user id itself, so this extends
 * {@link UserKeyedRepository} — the base that exists for exactly this table and
 * {@code ai_settings} (§8 of handoff 10). {@code UserOwnedRepository} would fail the Spring
 * context at startup with {@code No property 'id' found for type NotificationPreference}, because
 * it declares {@code findByIdAndUserId} and the entity has no id property to derive it from.
 *
 * <p>That is not a stylistic choice: a household administrator may remove a member and clear their
 * second factor, but nobody — administrator included — reads another member's notification
 * settings. With this base there is no unscoped finder to reach for by accident.
 *
 * <p>The two queries below are the deliberate exceptions, and both are jobs rather than requests:
 * they run with no caller and therefore no user to scope by. Neither is reachable from a
 * controller.
 */
public interface NotificationPreferenceRepository
    extends UserKeyedRepository<NotificationPreference> {

    /**
     * One page of the ids of members who are in the weekly digest.
     *
     * <p><strong>Enumerated from {@code users}, LEFT joined to this table, and a member with no
     * row here is a recipient.</strong> That direction is the whole point and it was wrong once:
     * an INNER join reads "only members who have a preference row", and nothing creates that row
     * at registration — it is created lazily, on the first settings read or the first notification
     * of any kind. So a member who registers, never opens the notification-settings screen and
     * whose household never crosses a budget threshold had NO row, and was silently and
     * permanently excluded from a digest V31 enables for them by default
     * ({@code weekly_digest_enabled BOOLEAN NOT NULL DEFAULT true}). A missing row means
     * "the shipped defaults", never "not interested", and only an explicit {@code false} opts a
     * member out.
     *
     * <p>Joined to {@code users} so that a deactivated account is not emailed: a member an
     * administrator has switched off should stop hearing from this server, and the preference row
     * survives the deactivation on purpose so that re-activating restores their settings.
     *
     * <p>Returns ids rather than entities because the digest loads each recipient's own user row
     * anyway (for the timezone and the currency), and a page of detached preference entities would
     * be a second copy of state the job then has to keep in step.
     *
     * <p>Ordered by user id so that paging is stable: {@code created_at} is not unique and a page
     * boundary inside a tie would skip or repeat a recipient.
     */
    @Query("select u.id from User u left join NotificationPreference p on p.userId = u.id "
        + "where u.active = true and (p.userId is null or p.weeklyDigestEnabled = true) "
        + "order by u.id asc")
    List<UUID> findDigestRecipients(Pageable page);

    /**
     * Every distinct budget-alert threshold held by the ACTIVE members of one household, as the
     * fraction {@code EnvelopeSpendService} compares against.
     *
     * <p>This is collapse step 11's half of the step-8 swap. Step 8 published
     * {@code BudgetThresholdCrossedEvent} against a single configured constant because this table
     * did not exist yet and said so in its own javadoc; now it does. Distinct values rather than
     * one per member, because the envelope only needs to know WHICH ratios to watch — deciding
     * which members to tell is the notification listener's job, and it reads each member's own row
     * for that.
     *
     * <p>A member with no preference row contributes nothing here; the caller falls back to the
     * shipped default, which is what that member's row would have carried anyway.
     */
    /**
     * Inserts this member's row with V31's own column defaults, or does nothing when it is already
     * there.
     *
     * <p>The read-then-{@code save} it replaces was a check-then-act on a table whose primary key
     * is {@code user_id}: two producers reaching a member who has never had a row — which the
     * budget listener really can do, since it walks every household member inside its own
     * {@code REQUIRES_NEW} transaction — both found the {@code Optional} empty and both INSERTed,
     * and the loser took a primary-key violation that rolled back not just its own notification
     * but every notification that pass had already written. {@code ON CONFLICT DO NOTHING} makes
     * the database decide the race, which is what every other race in this step already does
     * ({@code NotificationLogRepository.claim}, {@code WebhookDeliveryStore.claimDue}).
     *
     * <p>Native SQL for two reasons: JPQL has no {@code ON CONFLICT}, and naming only
     * {@code user_id} means the values come from V31's own {@code DEFAULT} clauses rather than
     * from a second copy of them in Java — so the shipped defaults cannot drift between the
     * migration and the entity. {@link NotificationPreference#defaultsFor} remains the in-memory
     * statement of the same values, and {@code NotificationPreferenceIT} asserts a real
     * first-access row against them.
     *
     * @return 1 when this call created the row, 0 when another transaction already had
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO notification_preference (user_id) VALUES (:userId) "
        + "ON CONFLICT DO NOTHING", nativeQuery = true)
    int insertDefaults(@Param("userId") UUID userId);

    @Query("select distinct p.budgetAlertThresholdPercent from NotificationPreference p "
        + "join User u on u.id = p.userId "
        + "where u.householdId = :householdId and u.active = true")
    List<Short> findThresholdPercentsForHousehold(@Param("householdId") UUID householdId);
}
